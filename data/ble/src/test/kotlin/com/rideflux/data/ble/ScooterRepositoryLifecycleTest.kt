package com.rideflux.data.ble

import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.safety.MotionInterlock
import com.rideflux.domain.transport.BleTransport
import com.rideflux.protocol.familyscooter.connection.ScooterConnectionImpl
import com.rideflux.protocol.familyscooter.ninebot.ScooterHandshakeStateMachine
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
}
