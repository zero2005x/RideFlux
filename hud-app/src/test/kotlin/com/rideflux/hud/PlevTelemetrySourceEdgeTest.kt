package com.rideflux.hud

import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.connection.WheelConnection
import io.mockk.coEvery
import io.mockk.mockk
import com.rideflux.domain.connection.ScooterHandshakeState
import com.rideflux.domain.device.PlevDevice
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.repository.PlevConnectionHandle
import com.rideflux.domain.repository.PlevRepository
import com.rideflux.domain.telemetry.ScooterTelemetry
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.hud.source.PlevTelemetrySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlevTelemetrySourceEdgeTest {
    private class FakeScooter(initial: ConnectionState) : ScooterConnection {
        override val device = ScooterDevice("AA", "ES2")
        override val state = MutableStateFlow(initial)
        override val telemetry = MutableStateFlow<ScooterTelemetry?>(null)
        override val handshakeState = MutableStateFlow(ScooterHandshakeState.UNBONDED)
        var closed = false
        override suspend fun start() = Unit
        override suspend fun close() { closed = true }
        override suspend fun lock(): CommandOutcome = error("unused")
        override suspend fun unlock(): CommandOutcome = error("unused")
        override suspend fun readRegister(register: Int, readLength: Int): ByteArray? = null
    }

    private fun repository(handle: PlevConnectionHandle) = object : PlevRepository {
        override fun scan(): Flow<List<PlevDevice>> = emptyFlow()
        override suspend fun connect(address: String): PlevConnectionHandle = handle
        override fun activeConnections(): Flow<Map<String, PlevConnectionHandle>> = emptyFlow()
    }

    @Test fun `connection states map to signal quality and nothing is marked stale`() = runTest {
        val cases = listOf(
            ConnectionState.Ready to SignalQuality.GOOD,
            ConnectionState.Connecting to SignalQuality.WEAK,
            ConnectionState.ScooterHandshaking to SignalQuality.WEAK,
            ConnectionState.Handshaking(WheelFamily.G) to SignalQuality.WEAK,
            ConnectionState.Disconnected to SignalQuality.NONE,
            ConnectionState.Failed(ConnectionState.Failed.Reason.GATT_ERROR, null) to SignalQuality.NONE,
        )
        for ((state, signal) in cases) {
            val scooter = FakeScooter(state)
            val frame = PlevTelemetrySource(repository(PlevConnectionHandle.Scooter(scooter)), "AA").frames().first()
            assertEquals(signal, frame.signal)
            assertEquals(false, frame.staleHint)
            assertNull(frame.scooterTelemetry)
            assertTrue(scooter.closed)
        }
    }

    @Test fun `a later state change produces another frame`() = runTest {
        val scooter = FakeScooter(ConnectionState.Connecting)
        val source = PlevTelemetrySource(repository(PlevConnectionHandle.Scooter(scooter)), "AA")
        scooter.telemetry.value = ScooterTelemetry(5L, speedKmh = 3f)
        val frames = source.frames().take(1).toList()
        assertEquals(3f, frames.single().scooterTelemetry?.speedKmh)
    }

    @Test fun `scooter HUD values tolerate missing and oversized fields`() {
        val empty = scooterHudValues(ScooterTelemetry(1L))
        assertNull(empty.speedKmh)
        assertNull(empty.vehicleBatteryPercent)
        assertNull(empty.tripDistanceMetres)
        val huge = scooterHudValues(ScooterTelemetry(1L, tripDistanceMetres = Long.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, huge.tripDistanceMetres)
    }

    @Test fun `a handle that is not a scooter is rejected and still closed`() = runTest {
        var closed = false
        val wheel = mockk<WheelConnection>()
        coEvery { wheel.close() } coAnswers { closed = true }
        val wheelLike = PlevConnectionHandle.Wheel(wheel, "AA")
        try {
            PlevTelemetrySource(repository(wheelLike), "AA").frames().first()
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("No verified scooter connection"))
        }
        assertTrue(closed)
    }
}
