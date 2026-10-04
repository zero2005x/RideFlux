package com.rideflux.protocol.familyscooter

import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.connection.ScooterHandshakeState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.device.WheelDevice
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.PlevConnectionHandle
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.telemetry.ScooterTelemetry
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.protocol.repository.PlevRepositoryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlevRepositoryImplTest {
    @Test fun `unified scan and connection route scooter through injected provider`() = runBlocking {
        val scooter = FakeScooter()
        val wheels = object : WheelRepository {
            override fun scan() = flowOf(listOf(DiscoveredWheel("wheel", "EUC", -40, WheelFamily.G)))
            override suspend fun connect(address: String, expectedFamily: WheelFamily?): WheelConnection =
                error("scooter must not be sent to wheel repository")
            override fun activeConnections(): Flow<Map<String, WheelConnection>> = flowOf(emptyMap())
        }
        val repository = PlevRepositoryImpl(wheels,
            scooters = flowOf(listOf(scooter.device)),
            connectScooter = { if (it == scooter.device.address) scooter else null })
        val found = repository.scan().first()
        assertTrue(found[0] is WheelDevice)
        assertEquals(scooter.device, found[1])
        val handle = repository.connect(scooter.device.address)
        assertTrue(handle is PlevConnectionHandle.Scooter)
        assertEquals(scooter.device.address, handle.address)
        handle.close()
        assertTrue(scooter.closed)
    }

    private class FakeScooter : ScooterConnection {
        override val device = ScooterDevice("scooter", "ES2")
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
        override val telemetry = MutableStateFlow<ScooterTelemetry?>(null)
        override val handshakeState = MutableStateFlow(ScooterHandshakeState.READY_FOR_TELEMETRY)
        var closed = false
        override suspend fun start() = Unit
        override suspend fun close() { closed = true }
        override suspend fun lock(): CommandOutcome = error("not used")
        override suspend fun unlock(): CommandOutcome = error("not used")
        override suspend fun readRegister(register: Int, readLength: Int): ByteArray? = null
    }
}
