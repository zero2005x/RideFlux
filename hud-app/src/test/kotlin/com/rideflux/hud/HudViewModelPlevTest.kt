package com.rideflux.hud

import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.connection.ScooterHandshakeState
import com.rideflux.domain.device.PlevDevice
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.repository.PlevConnectionHandle
import com.rideflux.domain.repository.PlevRepository
import com.rideflux.domain.telemetry.ScooterTelemetry
import com.rideflux.hud.source.PlevTelemetrySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HudViewModelPlevTest {
    @Test fun `direct scooter source maps speed battery and trip to HUD`() = runTest {
        val scooter = FakeScooter()
        val repository = object : PlevRepository {
            override fun scan(): Flow<List<PlevDevice>> = flowOf(listOf(scooter.device))
            override suspend fun connect(address: String): PlevConnectionHandle {
                assertEquals(scooter.device.address, address)
                return PlevConnectionHandle.Scooter(scooter)
            }
            override fun activeConnections(): Flow<Map<String, PlevConnectionHandle>> = flowOf(emptyMap())
        }
        val frame = PlevTelemetrySource(repository, scooter.device.address).frames().first()
        assertEquals(ConnectionState.Ready, frame.state)
        val values = scooterHudValues(requireNotNull(frame.scooterTelemetry))
        assertEquals(18f, values.speedKmh)
        assertEquals(67f, values.vehicleBatteryPercent)
        assertEquals(1_250, values.tripDistanceMetres)
        assertTrue(scooter.closed)
    }

    private class FakeScooter : ScooterConnection {
        override val device = ScooterDevice("AA:BB:CC:DD:EE:FF", "ES2")
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
        override val telemetry = MutableStateFlow<ScooterTelemetry?>(
            ScooterTelemetry(1_000, speedKmh = 18f, batteryPercent = 67f,
                tripDistanceMetres = 1_250))
        override val handshakeState = MutableStateFlow(ScooterHandshakeState.READY_FOR_TELEMETRY)
        var closed = false
        override suspend fun start() = Unit
        override suspend fun close() { closed = true }
        override suspend fun lock(): CommandOutcome = error("not used")
        override suspend fun unlock(): CommandOutcome = error("not used")
        override suspend fun readRegister(register: Int, readLength: Int): ByteArray? = null
    }
}
