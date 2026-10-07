package com.rideflux.data.ble

import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.safety.MotionInterlock
import com.rideflux.domain.transport.BleTransport
import com.rideflux.protocol.familyscooter.connection.ScooterConnectionImpl
import com.rideflux.protocol.familyscooter.ninebot.ScooterHandshakeStateMachine
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScooterRepositoryLifecycleTest {
    private class FakeBleTransport : BleTransport {
        private val packets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
        override val incoming: Flow<ByteArray> = packets
        var disconnectCount = 0
        val writes = mutableListOf<ByteArray>()
        override suspend fun connect() = Unit
        override suspend fun disconnect() { disconnectCount++ }
        override suspend fun write(bytes: ByteArray) { writes += bytes.copyOf() }
        fun emit(bytes: ByteArray) { assertTrue(packets.tryEmit(bytes)) }
    }

    @Test fun sharedConnectionClosesOnlyAfterLastHandle() = runTest {
        val transports = mutableListOf<FakeBleTransport>()
        val repository = ScooterRepositoryImpl(backgroundScope) { address, model, scope ->
            val ble = FakeBleTransport().also(transports::add)
            val gate = MotionInterlock()
            ScooterConnectionImpl(ble, ScooterDevice(address, model), scope,
                ScooterHandshakeStateMachine(gate) { testScheduler.currentTime }, gate,
                clock = { testScheduler.currentTime })
        }
        val first = repository.connect("scooter")
        val second = repository.connect("scooter")
        runCurrent()
        assertEquals(1, transports.size)
        assertTrue(repository.activeConnections().first().containsKey("scooter"))
        first.close(); first.close()
        assertEquals(0, transports.single().disconnectCount)
        second.close()
        assertEquals(1, transports.single().disconnectCount)
        assertTrue(repository.activeConnections().first().isEmpty())
    }

    @Test fun failedEntryIsRebuiltAndOldHandleCannotCloseSuccessor() = runTest {
        val transports = mutableListOf<FakeBleTransport>()
        val repository = ScooterRepositoryImpl(backgroundScope) { address, model, scope ->
            val ble = FakeBleTransport().also(transports::add)
            val gate = MotionInterlock()
            ScooterConnectionImpl(ble, ScooterDevice(address, model), scope,
                ScooterHandshakeStateMachine(gate) { testScheduler.currentTime }, gate,
                clock = { testScheduler.currentTime })
        }
        val old = repository.connect("scooter")
        runCurrent()
        // Valid 0x5B response reaches the stationary gate, which denies unverified speed.
        transports.single().emit(byteArrayOf(0x5a, 0xa5.toByte(), 1, 4, 0x3e,
            0x5b, 0, 1, 0x60, 0xff.toByte()))
        runCurrent()
        assertTrue(old.state.value is ConnectionState.Failed)
        val replacement = repository.connect("scooter")
        runCurrent()
        assertEquals(2, transports.size)
        old.close()
        assertEquals(0, transports.last().disconnectCount)
        replacement.close()
        assertEquals(1, transports.last().disconnectCount)
    }

    @Test fun removalClosesOnlySelectedSessionBeforeRemovingKey() = runTest {
        val removed = mutableListOf<String>()
        val transports = mutableMapOf<String, FakeBleTransport>()
        val store = object : com.rideflux.domain.bond.BondStore {
            override suspend fun list() = emptyList<com.rideflux.domain.bond.BondEntry>()
            override suspend fun put(entry: com.rideflux.domain.bond.BondEntry) = Unit
            override suspend fun remove(mac: String): Boolean {
                assertEquals(1, transports.entries.single { it.key.equals(mac, ignoreCase = true) }.value.disconnectCount)
                removed += mac
                return true
            }
        }
        val repo = ScooterRepositoryImpl(backgroundScope, store) { address, model, scope ->
            val ble = FakeBleTransport().also { transports[address] = it }
            val gate = MotionInterlock()
            ScooterConnectionImpl(ble, ScooterDevice(address, model), scope,
                ScooterHandshakeStateMachine(gate) { testScheduler.currentTime }, gate)
        }
        val old = repo.connect("aa:bb:cc:dd:ee:01")
        repo.connect("AA:BB:CC:DD:EE:02")
        runCurrent()
        repo.removePairingKey("aa:bb:cc:dd:ee:01")
        assertEquals(listOf("AA:BB:CC:DD:EE:01"), removed)
        assertEquals(0, transports.getValue("AA:BB:CC:DD:EE:02").disconnectCount)
        assertEquals(setOf("AA:BB:CC:DD:EE:02"), repo.activeConnections().first().keys)
        repo.connect("AA:BB:CC:DD:EE:01")
        runCurrent()
        old.close()
        assertEquals(0, transports.getValue("AA:BB:CC:DD:EE:01").disconnectCount)
    }

    @Test fun removalJoinsRegistrationBeforeDurableDeleteAndBlocksReconnect() = runTest {
        val mac = "AA:BB:CC:DD:EE:01"
        val operations = mutableListOf<String>()
        val closing = kotlinx.coroutines.CompletableDeferred<Unit>()
        val enteredClose = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = object : com.rideflux.domain.bond.BondStore {
            override suspend fun list() = emptyList<com.rideflux.domain.bond.BondEntry>()
            override suspend fun put(entry: com.rideflux.domain.bond.BondEntry) { operations += "put" }
            override suspend fun remove(mac: String): Boolean { operations += "remove"; return true }
        }
        var created = 0
        val repo = ScooterRepositoryImpl(backgroundScope, store) { address, model, _ ->
            created++
            object : com.rideflux.domain.connection.ScooterConnection {
                override val device = ScooterDevice(address, model)
                override val state = kotlinx.coroutines.flow.MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
                override val telemetry = kotlinx.coroutines.flow.MutableStateFlow<com.rideflux.domain.telemetry.ScooterTelemetry?>(null)
                override val handshakeState = kotlinx.coroutines.flow.MutableStateFlow(com.rideflux.domain.connection.ScooterHandshakeState.UNBONDED)
                override suspend fun start() {
                    try { kotlinx.coroutines.awaitCancellation() }
                    finally { operations += "registrationFinished" }
                }
                override suspend fun close() { enteredClose.complete(Unit); closing.await() }
                override suspend fun lock() = com.rideflux.domain.command.CommandOutcome.Unsupported(com.rideflux.domain.command.WheelCommand.Raw(byteArrayOf()))
                override suspend fun unlock() = lock()
                override suspend fun readRegister(register: Int, readLength: Int): ByteArray? = null
            }
        }
        repo.connect(mac)
        runCurrent()
        val delete = backgroundScope.launch { repo.removePairingKey(mac) }
        enteredClose.await()
        val reconnect = backgroundScope.launch { repo.connect(mac) }
        runCurrent()
        assertEquals(1, created)
        closing.complete(Unit)
        delete.join(); reconnect.join()
        assertEquals(listOf("registrationFinished", "remove"), operations)
        assertEquals(2, created)
    }
}
