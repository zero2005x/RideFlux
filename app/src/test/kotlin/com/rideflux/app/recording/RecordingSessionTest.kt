package com.rideflux.app.recording

import com.rideflux.core.location.TripLocation
import com.rideflux.core.location.TripLocationSource
import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.ride.Trip
import com.rideflux.domain.ride.TripRepository
import com.rideflux.domain.ride.TripSample
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingSessionTest {
    @Test fun createFailureReleasesConnectionAndLocation() = runTest {
        val fixture = Fixture()
        fixture.trips.createError = IOException("database full")
        fixture.session { 1_000L }.record("wheel", WheelFamily.G)
        assertEquals(1, fixture.connection.closeCount)
        assertFalse(fixture.locations.active)
        assertFalse(fixture.state.value.isRecording)
        assertNull(fixture.trips.finished)
        assertEquals(1, fixture.errors.size)
    }

    @Test fun appendAndFinishFailuresStillReleaseResources() = runTest {
        val fixture = Fixture()
        fixture.trips.appendError = IOException("sample write failed")
        fixture.trips.finishError = IOException("summary write failed")
        fixture.session { 1_000L }.record("wheel", WheelFamily.G)
        assertEquals(1, fixture.connection.closeCount)
        assertFalse(fixture.locations.active)
        assertFalse(fixture.state.value.isRecording)
        assertEquals(2, fixture.errors.size)
    }

    @Test fun cancellationFinalizesTripAndReleasesLocationAndConnection() = runTest {
        val fixture = Fixture()
        val job = launch { fixture.session { 1_000L + testScheduler.currentTime }.record("wheel", WheelFamily.G) }
        runCurrent()
        assertTrue(fixture.state.value.isRecording)
        assertTrue(fixture.locations.active)
        job.cancelAndJoin()
        assertEquals(1, fixture.connection.closeCount)
        assertFalse(fixture.locations.active)
        assertNotNull(fixture.trips.finished?.endedAtMillis)
        assertFalse(fixture.state.value.isRecording)
        assertTrue(fixture.errors.isEmpty())
    }

    @Test fun initialDisconnectedDoesNotEndBeforeAsyncConnectStarts() = runTest {
        val fixture = Fixture()
        fixture.connection.state.value = ConnectionState.Disconnected
        val job = launch { fixture.session { 1_000L + testScheduler.currentTime }.record("wheel", WheelFamily.G) }
        runCurrent()
        assertEquals(0, fixture.connection.closeCount)
        assertTrue(fixture.trips.samples.isEmpty())
        fixture.connection.state.value = ConnectionState.Ready
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, fixture.trips.samples.size)
        job.cancelAndJoin()
    }

    @Test fun silentWheelDoesNotRecordOldSpeedForever() = runTest {
        val fixture = Fixture()
        fixture.session { 1_000L + testScheduler.currentTime }.record("wheel", WheelFamily.G)
        assertEquals(4, fixture.trips.samples.size)
        assertEquals(30.0, fixture.trips.finished!!.distanceMetres, 0.001)
        assertEquals(1, fixture.connection.closeCount)
        assertFalse(fixture.state.value.isRecording)
    }

    @Test fun locationFailureDoesNotCrashTelemetryRecording() = runTest {
        val fixture = Fixture()
        fixture.locations.failure = SecurityException("location permission revoked")
        val job = launch { fixture.session { 1_000L }.record("wheel", WheelFamily.G) }
        runCurrent()
        assertTrue(fixture.state.value.isRecording)
        assertEquals(1, fixture.errors.size)
        job.cancelAndJoin()
        assertEquals(1, fixture.connection.closeCount)
    }

    @Test fun cancellationWhileCreatingTripStillClosesConnection() = runTest {
        val fixture = Fixture()
        fixture.trips.createError = CancellationException("cancelled")
        try {
            fixture.session { 1_000L }.record("wheel", WheelFamily.G)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, fixture.connection.closeCount)
        assertFalse(fixture.locations.active)
        assertTrue(fixture.errors.isEmpty())
    }

    private class Fixture {
        val connection = FakeConnection()
        val trips = FakeTrips()
        val locations = FakeLocations()
        val state = MutableStateFlow(RecordingUiState())
        val errors = mutableListOf<Throwable>()
        private val wheels = object : WheelRepository {
            override fun scan() = flowOf(emptyList<DiscoveredWheel>())
            override fun activeConnections() = flowOf(emptyMap<String, WheelConnection>())
            override suspend fun connect(address: String, expectedFamily: WheelFamily?) = connection
        }
        fun session(clock: () -> Long) = RecordingSession(wheels, trips, locations, state, { errors.add(it) }, clock)
    }

    private class FakeLocations : TripLocationSource {
        var active = false
        var failure: Exception? = null
        override fun hasPermission() = true
        override fun locations() = flow<TripLocation> {
            failure?.let { throw it }
            active = true
            try { awaitCancellation() } finally { active = false }
        }
    }

    private class FakeTrips : TripRepository {
        var createError: Exception? = null
        var appendError: Exception? = null
        var finishError: Exception? = null
        var finished: Trip? = null
        val samples = mutableListOf<TripSample>()
        override suspend fun createTrip(trip: Trip): Long { createError?.let { throw it }; return 1 }
        override suspend fun appendSample(sample: TripSample) { appendError?.let { throw it }; samples.add(sample) }
        override suspend fun finishTrip(trip: Trip) { finishError?.let { throw it }; finished = trip }
        override fun observeTrips(wheelAddress: String?) = flowOf(emptyList<Trip>())
        override fun observeTrip(tripId: Long) = flowOf<Trip?>(null)
        override fun observeSamples(tripId: Long) = flowOf(samples.toList())
        override suspend fun deleteTrip(tripId: Long) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun recoverIncompleteTrips() = Unit
    }

    private class FakeConnection : WheelConnection {
        var closeCount = 0
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
        override val telemetry = MutableStateFlow(WheelTelemetry(timestampMillis = 1_000L, speedKmh = 36f))
        override val identity = MutableStateFlow<WheelIdentity?>(null)
        override val capabilities = MutableStateFlow<WheelCapabilities?>(null)
        override val alerts = MutableSharedFlow<WheelAlert>()
        override val speedKmh = MutableStateFlow<Float?>(36f)
        override val voltageV = MutableStateFlow<Float?>(null)
        override val batteryPercent = MutableStateFlow<Float?>(null)
        override val currentA = MutableStateFlow<Float?>(null)
        override val mosTemperatureC = MutableStateFlow<Float?>(null)
        override val totalDistanceMetres = MutableStateFlow<Long?>(null)
        override suspend fun dispatch(command: WheelCommand) = CommandOutcome.Success
        override suspend fun close() { closeCount++ }
    }
}
