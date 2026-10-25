package com.rideflux.protocol.familyscooter

import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.connection.ScooterHandshakeState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.device.WheelDevice
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.PlevConnectionHandle
import com.rideflux.domain.repository.PlevRouteConflictException
import com.rideflux.domain.repository.ScooterRepository
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

    @Test fun `discovered scooter routes directly and connection failure never falls back to wheel`() = runBlocking {
        val wheels = object : WheelRepository {
            override fun scan() = flowOf(emptyList<DiscoveredWheel>())
            override suspend fun connect(address: String, expectedFamily: WheelFamily?): WheelConnection =
                error("must not open wheel transport for a scooter")
            override fun activeConnections(): Flow<Map<String, WheelConnection>> = flowOf(emptyMap())
        }
        val scooters = object : ScooterRepository {
            override fun scan() = flowOf(emptyList<ScooterDevice>())
            override fun isDiscovered(address: String) = address == "scooter"
            override suspend fun connect(address: String): ScooterConnection =
                throw IllegalStateException("Scooter profile unavailable")
            override fun activeConnections(): Flow<Map<String, ScooterConnection>> = flowOf(emptyMap())
        }
        val repository = PlevRepositoryImpl(wheels, scooters)
        val failure = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { repository.connect("scooter") }
        }
        assertEquals("Scooter profile unavailable", failure.message)
    }

    @Test fun `a persisted scooter category routes without any process scan`() = runBlocking {
        val scooter = FakeScooter()
        val wheels = object : WheelRepository {
            override fun scan() = flowOf(emptyList<DiscoveredWheel>())
            override suspend fun connect(address: String, expectedFamily: WheelFamily?): WheelConnection =
                error("saved scooter must not call wheel connect")
            override fun activeConnections(): Flow<Map<String, WheelConnection>> = flowOf(emptyMap())
        }
        val repository = PlevRepositoryImpl(
            wheels,
            connectScooter = { if (it == scooter.device.address) scooter else null },
        )

        val handle = repository.connect(scooter.device.address, PlevCategory.SCOOTER)
        assertTrue(handle is PlevConnectionHandle.Scooter)
        handle.close()
    }

    @Test fun `a saved scooter that discovery saw as a wheel fails closed`() = runBlocking {
        val wheels = object : WheelRepository {
            override fun scan() = flowOf(listOf(DiscoveredWheel("address", "EUC", -40, WheelFamily.G)))
            override suspend fun connect(address: String, expectedFamily: WheelFamily?): WheelConnection =
                error("conflict must not open wheel")
            override fun activeConnections(): Flow<Map<String, WheelConnection>> = flowOf(emptyMap())
        }
        val repository = PlevRepositoryImpl(wheels)
        repository.scan().first()

        val failure = assertThrows(PlevRouteConflictException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.connect("address", PlevCategory.SCOOTER)
            }
        }
        assertEquals(PlevCategory.SCOOTER, failure.requestedCategory)
    }

    @Test fun `a saved wheel that discovery saw as a scooter fails closed`() = runBlocking {
        val scooter = FakeScooter()
        val wheels = object : WheelRepository {
            override fun scan() = flowOf(emptyList<DiscoveredWheel>())
            override suspend fun connect(address: String, expectedFamily: WheelFamily?): WheelConnection =
                error("conflict must not open wheel")
            override fun activeConnections(): Flow<Map<String, WheelConnection>> = flowOf(emptyMap())
        }
        val repository = PlevRepositoryImpl(
            wheels,
            scooters = flowOf(listOf(scooter.device)),
            connectScooter = { scooter },
        )
        repository.scan().first()

        val failure = assertThrows(PlevRouteConflictException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.connect(scooter.device.address, PlevCategory.WHEEL)
            }
        }
        assertEquals(PlevCategory.WHEEL, failure.requestedCategory)
    }

    @Test fun `a persisted scooter transport hint is forwarded only to the scooter provider`() = runBlocking {
        val scooter = FakeScooter()
        val hints = mutableListOf<String?>()
        val wheels = object : WheelRepository {
            override fun scan() = flowOf(emptyList<DiscoveredWheel>())
            override suspend fun connect(address: String, expectedFamily: WheelFamily?): WheelConnection =
                error("hinted scooter must not open wheel transport")
            override fun activeConnections(): Flow<Map<String, WheelConnection>> = flowOf(emptyMap())
        }
        val repository = PlevRepositoryImpl(
            wheels,
            scooters = flowOf(listOf(scooter.device)),
            connectScooterWithModel = { _, hint ->
                hints += hint
                scooter
            },
        )
        repository.scan().first()
        val handle = repository.connect(scooter.device.address, PlevCategory.SCOOTER, modelHint = "ES2")
        assertTrue(handle is PlevConnectionHandle.Scooter)
        assertEquals(listOf<String?>("ES2"), hints)
        handle.close()
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
