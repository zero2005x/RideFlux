package com.wheellog.next.domain.usecase

import com.wheellog.next.domain.model.TelemetryState
import com.wheellog.next.domain.model.Trip
import com.wheellog.next.domain.model.WheelType
import com.wheellog.next.domain.repository.TripRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TripRecorderTest {

    private lateinit var recorder: TripRecorder
    private lateinit var fakeRepo: FakeTripRepository

    @Before
    fun setUp() {
        fakeRepo = FakeTripRepository()
        recorder = TripRecorder(fakeRepo)
    }

    @Test
    fun `isRecording is false by default`() {
        assertFalse(recorder.isRecording.value)
    }

    @Test
    fun `start sets isRecording to true`() {
        recorder.start()
        assertTrue(recorder.isRecording.value)
    }

    @Test
    fun `stop sets isRecording to false`() = runTest {
        recorder.start()
        recorder.stop()
        assertFalse(recorder.isRecording.value)
    }

    @Test
    fun `stop returns null when not recording`() = runTest {
        val trip = recorder.stop()
        assertNull(trip)
    }

    @Test
    fun `stop saves trip to repository`() = runTest {
        recorder.start(currentTripDistance = 10.0f)
        recorder.update(TelemetryState(speedKmh = 25f, tripDistanceKm = 12.5f))
        recorder.update(TelemetryState(speedKmh = 30f, tripDistanceKm = 13.0f))
        recorder.update(TelemetryState(speedKmh = 20f, tripDistanceKm = 13.5f))

        val trip = recorder.stop()
        assertNotNull(trip)
        assertEquals(1, fakeRepo.savedTrips.size)
    }

    @Test
    fun `records max speed correctly`() = runTest {
        recorder.start()
        recorder.update(TelemetryState(speedKmh = 10f))
        recorder.update(TelemetryState(speedKmh = 30f))
        recorder.update(TelemetryState(speedKmh = 20f))

        val trip = recorder.stop()!!
        assertEquals(30f, trip.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `records average speed correctly`() = runTest {
        recorder.start()
        recorder.update(TelemetryState(speedKmh = 10f))
        recorder.update(TelemetryState(speedKmh = 30f))
        recorder.update(TelemetryState(speedKmh = 20f))

        val trip = recorder.stop()!!
        assertEquals(20f, trip.avgSpeedKmh, 0.01f)
    }

    @Test
    fun `records distance based on trip odometer delta`() = runTest {
        recorder.start(currentTripDistance = 5.0f)
        recorder.update(TelemetryState(tripDistanceKm = 5.5f))
        recorder.update(TelemetryState(tripDistanceKm = 8.0f))

        val trip = recorder.stop()!!
        assertEquals(3.0f, trip.distanceKm, 0.01f)
    }

    @Test
    fun `update is no-op when not recording`() = runTest {
        recorder.update(TelemetryState(speedKmh = 50f))
        recorder.start()
        recorder.update(TelemetryState(speedKmh = 10f))

        val trip = recorder.stop()!!
        assertEquals(10f, trip.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `preserves device address and wheel type`() = runTest {
        recorder.start(
            deviceAddress = "AA:BB:CC:DD",
            wheelType = WheelType.KINGSONG,
        )
        recorder.update(TelemetryState(speedKmh = 5f))
        val trip = recorder.stop()!!

        assertEquals("AA:BB:CC:DD", trip.deviceAddress)
        assertEquals(WheelType.KINGSONG, trip.wheelType)
    }

    /** Minimal fake [TripRepository] for testing. */
    private class FakeTripRepository : TripRepository {
        val savedTrips = mutableListOf<Trip>()
        private val flow = MutableStateFlow<List<Trip>>(emptyList())

        override fun observeTrips(): Flow<List<Trip>> = flow

        override suspend fun getTripById(id: Long): Trip? =
            savedTrips.find { it.id == id }

        override suspend fun saveTrip(trip: Trip): Long {
            val id = (savedTrips.size + 1).toLong()
            savedTrips.add(trip.copy(id = id))
            flow.value = savedTrips.toList()
            return id
        }

        override suspend fun deleteTrip(id: Long) {
            savedTrips.removeAll { it.id == id }
            flow.value = savedTrips.toList()
        }
    }
}
