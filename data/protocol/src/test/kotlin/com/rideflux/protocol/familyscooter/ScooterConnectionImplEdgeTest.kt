package com.rideflux.protocol.familyscooter

import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.ConnectionState.Failed.Reason
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.safety.MotionInterlock
import com.rideflux.domain.transport.BleTransport
import com.rideflux.protocol.familyscooter.connection.ScooterConnectionImpl
import com.rideflux.protocol.familyscooter.ninebot.ScooterHandshakeStateMachine
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScooterConnectionImplEdgeTest {
    private class Transport(override val incoming: Flow<ByteArray> = MutableSharedFlow(extraBufferCapacity = 32)) : BleTransport {
        var failConnect = false
        var failWrites = false
        var failDisconnect = false
        var disconnects = 0
        val writes = mutableListOf<ByteArray>()
        override suspend fun connect() { if (failConnect) throw IOException("connect") }
        override suspend fun disconnect() { disconnects++; if (failDisconnect) throw IOException("disconnect") }
        override suspend fun write(bytes: ByteArray) {
            if (failWrites) throw IOException("write")
            writes += bytes.copyOf()
        }
        fun emit(bytes: ByteArray) { assertTrue((incoming as MutableSharedFlow).tryEmit(bytes)) }
    }

    private fun reply(command: Int, argument: Int = 0, payload: ByteArray = byteArrayOf(1),
                      source: Int = 0x04): ByteArray {
        val body = byteArrayOf(payload.size.toByte(), source.toByte(), 0x3e,
            command.toByte(), argument.toByte()) + payload
        return RetailFraming.appendChecksum(byteArrayOf(0x5a, 0xa5.toByte()) + body, body)
    }

    private fun TestScope.connection(
        transport: Transport, lockVerified: Boolean = false, speed: (ByteArray) -> Float? = { 0f },
        scope: CoroutineScope = backgroundScope,
    ): ScooterConnectionImpl {
        val gate = MotionInterlock()
        return ScooterConnectionImpl(transport, ScooterDevice("AA", "ES2"), scope,
            ScooterHandshakeStateMachine(gate) { testScheduler.currentTime }, gate,
            clock = { testScheduler.currentTime }, pollingIntervalMillis = 1_000L,
            verifiedSpeed = speed, appRandom = { ByteArray(16) { it.toByte() } },
            lockProfileVerified = lockVerified)
    }

    private fun TestScope.ready(transport: Transport, lockVerified: Boolean = false,
                                speed: (ByteArray) -> Float? = { 0f }): ScooterConnectionImpl {
        val connection = connection(transport, lockVerified, speed)
        return connection.also {
            kotlinx.coroutines.runBlocking { it.start() }
            runCurrent()
            repeat(3) { transport.emit(reply(0x01)); runCurrent() }
            transport.emit(reply(0x5b)); runCurrent()
            transport.emit(reply(0x5c, 1)); runCurrent()
            transport.emit(reply(0x5d)); runCurrent()
            assertEquals(ConnectionState.Ready, it.state.value)
        }
    }

    @Test fun `start failure is reported and rethrown`() = runTest {
        val transport = Transport().apply { failConnect = true }
        val connection = connection(transport)
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { connection.start() } }
        assertEquals(Reason.GATT_ERROR, (connection.state.value as ConnectionState.Failed).reason)
        runCurrent()
        assertEquals(1, transport.disconnects)
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { connection.start() } }
    }

    @Test fun `close is idempotent and survives a failing disconnect`() = runTest {
        val transport = Transport().apply { failDisconnect = true }
        val connection = connection(transport)
        connection.start(); runCurrent()
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { connection.close() } }
        assertEquals(ConnectionState.Disconnected, connection.state.value)
        connection.close()
        assertEquals(1, transport.disconnects)
        assertFalse(connection.lockSupported)
    }

    @Test fun `link loss and stream errors map to distinct failure reasons`() = runTest {
        val ended = connection(Transport(incoming = flow { }))
        ended.start(); runCurrent()
        assertEquals(Reason.BLE_LINK_LOST, (ended.state.value as ConnectionState.Failed).reason)
        val rejected = connection(Transport(incoming = flow { throw IllegalStateException("bad frame") }))
        rejected.start(); runCurrent()
        assertEquals(Reason.AUTHENTICATION_FAILED, (rejected.state.value as ConnectionState.Failed).reason)
        val broken = connection(Transport(incoming = flow { throw IOException("radio") }))
        broken.start(); runCurrent()
        assertEquals(Reason.INTERNAL, (broken.state.value as ConnectionState.Failed).reason)
    }

    @Test fun `register reads complete on reply and respect busy and timeout rules`() = runTest {
        val transport = Transport()
        val connection = ready(transport)
        val first = async { connection.readRegister(0x22, 2) }
        runCurrent()
        assertNull(connection.readRegister(0x23, 2))
        transport.emit(reply(0x22, 4, byteArrayOf(9, 8), source = 0x23)); runCurrent()
        assertArrayEquals(byteArrayOf(9, 8), first.await())
        val silent = async { connection.readRegister(0x24, 2) }
        runCurrent()
        advanceTimeBy(3_001); runCurrent()
        assertNull(silent.await())
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { connection.readRegister(256, 2) }
        }
        connection.close()
        assertNull(connection.readRegister(0x22, 2))
    }

    @Test fun `non B0 frames with a verified speed update telemetry speed only`() = runTest {
        val transport = Transport()
        var speed = 0f
        val connection = ready(transport, speed = { speed })
        speed = 7.5f
        transport.emit(reply(0x7a, 4, byteArrayOf(0, 0), source = 0x23)); runCurrent()
        assertEquals(7.5f, connection.telemetry.value?.speedKmh)
        assertNull(connection.telemetry.value?.batteryPercent)
    }

    @Test fun `controls refuse forbidden registers and report an unverified lock profile`() = runTest {
        val transport = Transport()
        val connection = ready(transport)
        assertTrue(connection.control(0x78) is CommandOutcome.InvalidArgument)
        repeat(3) { transport.emit(reply(0x01)); runCurrent() }
        assertTrue(connection.lock() is CommandOutcome.Unsupported)
        assertTrue(connection.unlock() is CommandOutcome.Unsupported)
        connection.close()
        assertTrue(connection.lock() is CommandOutcome.TransportError)
    }

    @Test fun `a failing lock write is a transport error`() = runTest {
        val transport = Transport()
        val connection = ready(transport, lockVerified = true)
        repeat(3) { transport.emit(reply(0x01)); runCurrent() }
        transport.failWrites = true
        assertTrue(connection.lock() is CommandOutcome.TransportError)
    }

    @Test fun `a failing poll write fails the connection with a GATT error`() = runTest {
        val transport = Transport()
        val connection = ready(transport)
        transport.failWrites = true
        advanceTimeBy(1_001); runCurrent()
        assertEquals(Reason.GATT_ERROR, (connection.state.value as ConnectionState.Failed).reason)
    }
}
