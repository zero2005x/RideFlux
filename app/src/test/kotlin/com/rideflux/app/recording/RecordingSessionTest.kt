/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.recording

import com.rideflux.core.location.TripLocation
import com.rideflux.core.location.TripLocationSource
import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.ride.ImportResult
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
import kotlinx.coroutines.delay
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
        // First stale poll at 4 s, then a full 30 s grace period.
        assertEquals(34_000L, testScheduler.currentTime)
        assertEquals(1, fixture.connection.closeCount)
        assertFalse(fixture.state.value.isRecording)
        assertEquals("ending_silence_timeout", fixture.events.last())
    }

    @Test fun fiveSecondSilenceResumesTheSameTripWithoutIntegratingTheGap() = runTest {
        val fixture = Fixture()
        val job = launch { fixture.session { 1_000L + testScheduler.currentTime }.record("wheel", WheelFamily.G) }
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue(job.isActive)
        assertTrue(fixture.state.value.isRecording)
        assertEquals(4, fixture.trips.samples.size)
        fixture.connection.telemetry.value = fixture.connection.telemetry.value.copy(timestampMillis = 6_000L)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, fixture.trips.createCount)
        assertTrue(fixture.trips.samples.all { it.tripId == 1L })
        // The first resumed sample establishes a new interval baseline.
        assertEquals(30.0, fixture.state.value.statistics.distanceMetres, 0.001)
        job.cancelAndJoin()
        assertEquals(1, fixture.connection.closeCount)
        assertEquals(1, fixture.trips.finishCount)
        assertEquals(listOf("started", "paused_stale_telemetry", "resumed", "ending_cancelled"), fixture.events)
    }

    @Test fun freshUnknownSpeedFor125SecondsDoesNotCauseIdleStop() = runTest {
        val fixture = Fixture()
        fixture.connection.telemetry.value = WheelTelemetry(timestampMillis = 1_000L)
        backgroundScope.launch {
            while (true) {
                fixture.connection.telemetry.value = WheelTelemetry(timestampMillis = 1_000L + testScheduler.currentTime)
                delay(1_000L)
            }
        }
        val job = launch { fixture.session { 1_000L + testScheduler.currentTime }.record("wheel", WheelFamily.G) }
        runCurrent()
        advanceTimeBy(125_000L)
        runCurrent()
        assertTrue(job.isActive)
        assertNull(fixture.state.value.statistics.avgSpeedKmh)
        assertNull(fixture.state.value.statistics.maxSpeedKmh)
        job.cancelAndJoin()
    }

    @Test fun freshStationarySpeedStillEndsAfter120Samples() = runTest {
        val fixture = Fixture()
        fixture.connection.telemetry.value = WheelTelemetry(timestampMillis = 1_000L, speedKmh = 0f)
        backgroundScope.launch {
            while (true) {
                fixture.connection.telemetry.value = WheelTelemetry(
                    timestampMillis = 1_000L + testScheduler.currentTime, speedKmh = 0f,
                )
                delay(1_000L)
            }
        }
        fixture.session { 1_000L + testScheduler.currentTime }.record("wheel", WheelFamily.G)
        assertEquals(120, fixture.trips.samples.size)
        assertEquals(1, fixture.trips.finishCount)
        assertEquals("ending_idle_timeout", fixture.events.last())
    }

    @Test fun initialConnectionWaitExpiresAfterThirtySeconds() = runTest {
        val fixture = Fixture()
        fixture.connection.state.value = ConnectionState.Disconnected
        val job = launch { fixture.session { 1_000L + testScheduler.currentTime }.record("wheel", WheelFamily.G) }
        runCurrent()
        advanceTimeBy(29_000L)
        runCurrent()
        assertTrue(job.isActive)
        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse(job.isActive)
        assertTrue(fixture.trips.samples.isEmpty())
        assertEquals(1, fixture.trips.finishCount)
        assertEquals(1, fixture.connection.closeCount)
        assertFalse(fixture.locations.active)
    }

    @Test fun aBriefDisconnectBreaksTheDistanceIntervalEvenWithinTwoSeconds() = runTest {
        val fixture = Fixture()
        val job = launch { fixture.session { 1_000L + testScheduler.currentTime }.record("wheel", WheelFamily.G) }
        runCurrent()
        advanceTimeBy(1_000L)
        fixture.connection.state.value = ConnectionState.Disconnected
        runCurrent()
        assertTrue(fixture.state.value.isRecording)
        assertEquals(1, fixture.trips.samples.size)
        fixture.connection.state.value = ConnectionState.Ready
        fixture.connection.telemetry.value = WheelTelemetry(timestampMillis = 3_000L, speedKmh = 36f)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, fixture.trips.samples.size)
        assertEquals(0.0, fixture.state.value.statistics.distanceMetres, 0.001)
        assertEquals(1, fixture.trips.createCount)
        job.cancelAndJoin()
        assertEquals(1, fixture.trips.finishCount)
    }

    @Test fun cancellationDuringSilenceImmediatelyFinalizesAndReleasesResources() = runTest {
        val fixture = Fixture()
        val job = launch { fixture.session { 1_000L + testScheduler.currentTime }.record("wheel", WheelFamily.G) }
        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue(fixture.state.value.isRecording)
        job.cancelAndJoin()
        assertEquals(1, fixture.trips.finishCount)
        assertEquals(1, fixture.connection.closeCount)
        assertFalse(fixture.locations.active)
        assertFalse(fixture.state.value.isRecording)
    }

    @Test fun failedConnectionStillFinalizesImmediately() = runTest {
        val fixture = Fixture()
        fixture.connection.state.value = ConnectionState.Failed(ConnectionState.Failed.Reason.BLE_LINK_LOST)
        fixture.session { 1_000L + testScheduler.currentTime }.record("wheel", WheelFamily.G)
        assertTrue(fixture.trips.samples.isEmpty())
        assertEquals(1, fixture.connection.closeCount)
        assertEquals(1, fixture.trips.finishCount)
        assertEquals("ending_connection_failed_BLE_LINK_LOST", fixture.events.last())
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
        val events = mutableListOf<String>()
        private val wheels = object : WheelRepository {
            override fun scan() = flowOf(emptyList<DiscoveredWheel>())
            override fun activeConnections() = flowOf(emptyMap<String, WheelConnection>())
            override suspend fun connect(address: String, expectedFamily: WheelFamily?) = connection
        }
        fun session(clock: () -> Long) = RecordingSession(
            wheels, trips, locations, state, { errors.add(it) }, clock, elapsedClock = clock,
            onEvent = { events.add(it) },
        )
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
        var createCount = 0
        var finishCount = 0
        var createError: Exception? = null
        var appendError: Exception? = null
        var finishError: Exception? = null
        var finished: Trip? = null
        val samples = mutableListOf<TripSample>()
        override suspend fun createTrip(trip: Trip): Long { createError?.let { throw it }; createCount++; return 1 }
        override suspend fun appendSample(sample: TripSample) { appendError?.let { throw it }; samples.add(sample) }
        override suspend fun finishTrip(trip: Trip) { finishError?.let { throw it }; finishCount++; finished = trip }
        override fun observeTrips(wheelAddress: String?) = flowOf(emptyList<Trip>())
        override fun observeTrip(tripId: Long) = flowOf<Trip?>(null)
        override fun observeSamples(tripId: Long) = flowOf(samples.toList())
        override suspend fun deleteTrip(tripId: Long) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun recoverIncompleteTrips() = Unit
        override suspend fun getAllTrips() = emptyList<Trip>()
        override suspend fun getAllSamples() = samples.toList()
        override suspend fun getSamples(tripId: Long) = samples.toList()
        override suspend fun importTrips(
            tripsWithSamples: List<Pair<Trip, List<TripSample>>>,
            replaceAll: Boolean,
        ) = ImportResult(tripsImported = 0, tripsSkipped = 0, samplesImported = 0)
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
