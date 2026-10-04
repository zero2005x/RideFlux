package com.rideflux.protocol.familyscooter

import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.ScooterHandshakeState
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.safety.MotionInterlock
import com.rideflux.domain.transport.BleTransport
import com.rideflux.protocol.familyscooter.connection.ScooterConnectionImpl
import com.rideflux.protocol.familyscooter.ninebot.NinebotRetailCodec
import com.rideflux.protocol.familyscooter.ninebot.ScooterHandshakeStateMachine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScooterConnectionImplTest {
    private class FakeTransport : BleTransport {
        val frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
        val writes = mutableListOf<ByteArray>()
        var connected = false
        override val incoming: Flow<ByteArray> = frames
        override suspend fun connect() { connected = true }
        override suspend fun disconnect() { connected = false }
        override suspend fun write(bytes: ByteArray) { writes += bytes.copyOf() }
        fun emit(bytes: ByteArray) { assertTrue(frames.tryEmit(bytes)) }
    }

    private fun reply(command: Int, argument: Int = 0, payload: ByteArray = byteArrayOf(),
                      source: Int = 0x04): ByteArray {
        val body = byteArrayOf(payload.size.toByte(), source.toByte(), 0x3e,
            command.toByte(), argument.toByte()) + payload
        return RetailFraming.appendChecksum(byteArrayOf(0x5a, 0xa5.toByte()) + body, body)
    }

    @Test fun `full handshake reaches ready and B0 polling updates battery without invented speed`() = runTest {
        val ble = FakeTransport()
        val gate = MotionInterlock()
        var speed = 0f
        val machine = ScooterHandshakeStateMachine(gate) { testScheduler.currentTime }
        val connection = ScooterConnectionImpl(ble, ScooterDevice("AA", "ES2"), backgroundScope,
            machine, gate, { testScheduler.currentTime }, 1_000L,
            verifiedSpeed = { speed },
            appRandom = { ByteArray(16) { it.toByte() } }, lockProfileVerified = true)
        connection.start()
        assertTrue(connection.lockSupported)
        runCurrent()
        assertArrayEquals(NinebotRetailCodec.buildHandshakeStep1(), ble.writes.single())
        assertEquals(ConnectionState.ScooterHandshaking, connection.state.value)
        repeat(3) { ble.emit(reply(0x01)); runCurrent() }
        val randomReply = reply(0x5b, payload = byteArrayOf(1))
        ble.emit(randomReply.copyOfRange(0, 4)); runCurrent()
        assertEquals(1, ble.writes.size)
        ble.emit(randomReply.copyOfRange(4, randomReply.size))
        runCurrent()
        assertEquals(0x5c, NinebotRetailCodec.decodeFrame(ble.writes.last())?.command)
        assertEquals(ScooterHandshakeState.WAITING_FOR_USER_CONFIRMATION, connection.handshakeState.value)
        ble.emit(reply(0x5c, argument = 1))
        runCurrent()
        assertEquals(0x5d, NinebotRetailCodec.decodeFrame(ble.writes.last())?.command)
        assertArrayEquals(ByteArray(16) { it.toByte() },
            NinebotRetailCodec.decodeFrame(ble.writes.last())?.payload)
        ble.emit(reply(0x5d))
        runCurrent()
        assertEquals(ConnectionState.Ready, connection.state.value)
        assertEquals(ScooterHandshakeState.READY_FOR_TELEMETRY, connection.handshakeState.value)
        assertArrayEquals(NinebotRetailCodec.buildReadRequest(0xb0, 32), ble.writes.last())
        val block = ByteArray(32).apply { this[8] = 72 }
        ble.emit(reply(0xb0, argument = 4, payload = block, source = 0x23))
        runCurrent()
        assertEquals(72f, connection.telemetry.value?.batteryPercent)
        assertEquals(0f, connection.telemetry.value?.speedKmh)
        speed = 15f
        ble.emit(reply(0xb0, argument = 4, payload = block, source = 0x23)); runCurrent()
        assertTrue(connection.lock() is CommandOutcome.InvalidArgument)
        speed = 0f
        repeat(3) {
            ble.emit(reply(0xb0, argument = 4, payload = block, source = 0x23)); runCurrent()
        }
        assertEquals(CommandOutcome.Success, connection.lock())
        assertArrayEquals(NinebotRetailCodec.buildLockRequest(gate, testScheduler.currentTime), ble.writes.last())
        assertEquals(CommandOutcome.Success, connection.unlock())
        assertArrayEquals(NinebotRetailCodec.buildUnlockRequest(gate, testScheduler.currentTime), ble.writes.last())
        assertTrue(connection.control(0x78) is CommandOutcome.InvalidArgument)
        assertTrue(connection.control(0x79) is CommandOutcome.InvalidArgument)
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(ble.writes.count { NinebotRetailCodec.decodeFrame(it)?.command == 0x01 } >= 2)
        connection.close()
        assertEquals(ConnectionState.Disconnected, connection.state.value)
        assertFalse(ble.connected)
    }

    @Test fun `confirmation timeout fails connection after twenty seconds`() = runTest {
        val ble = FakeTransport()
        val gate = MotionInterlock()
        val machine = ScooterHandshakeStateMachine(gate) { testScheduler.currentTime }
        val connection = ScooterConnectionImpl(ble, ScooterDevice("AA", "ES2"), backgroundScope,
            machine, gate, { testScheduler.currentTime }, verifiedSpeed = { 0f })
        connection.start(); runCurrent()
        repeat(3) { ble.emit(reply(0x01)); runCurrent() }
        ble.emit(reply(0x5b, payload = byteArrayOf(1))); runCurrent()
        advanceTimeBy(20_001); runCurrent()
        assertEquals(ConnectionState.Failed.Reason.HANDSHAKE_TIMEOUT,
            (connection.state.value as ConnectionState.Failed).reason)
        connection.close()
    }

    @Test fun `silent scooter after the first request fails after ten seconds`() = runTest {
        val ble = FakeTransport()
        val gate = MotionInterlock()
        val machine = ScooterHandshakeStateMachine(gate) { testScheduler.currentTime }
        val connection = ScooterConnectionImpl(ble, ScooterDevice("AA", "ES2"), backgroundScope,
            machine, gate, { testScheduler.currentTime }, verifiedSpeed = { 0f })
        connection.start(); runCurrent()
        advanceTimeBy(9_999); runCurrent()
        assertEquals(ConnectionState.ScooterHandshaking, connection.state.value)
        advanceTimeBy(2); runCurrent()
        assertEquals(ConnectionState.Failed.Reason.HANDSHAKE_TIMEOUT,
            (connection.state.value as ConnectionState.Failed).reason)
        assertEquals(ScooterHandshakeState.UNBONDED, connection.handshakeState.value)
        connection.close()
    }

    @Test fun `missing pairing acceptance fails after ten seconds`() = runTest {
        val ble = FakeTransport()
        val gate = MotionInterlock()
        val machine = ScooterHandshakeStateMachine(gate) { testScheduler.currentTime }
        val connection = ScooterConnectionImpl(ble, ScooterDevice("AA", "ES2"), backgroundScope,
            machine, gate, { testScheduler.currentTime }, verifiedSpeed = { 0f })
        connection.start(); runCurrent()
        repeat(3) { ble.emit(reply(0x01)); runCurrent() }
        ble.emit(reply(0x5b, payload = byteArrayOf(1))); runCurrent()
        ble.emit(reply(0x5c, argument = 1)); runCurrent()
        assertEquals(ScooterHandshakeState.AWAITING_PAIRING_ACCEPTANCE, connection.handshakeState.value)
        advanceTimeBy(10_001); runCurrent()
        assertEquals(ConnectionState.Failed.Reason.HANDSHAKE_TIMEOUT,
            (connection.state.value as ConnectionState.Failed).reason)
        connection.close()
    }

    @Test fun `unknown B0 sentinel revokes prior stationary lock permit`() = runTest {
        val ble = FakeTransport()
        val gate = MotionInterlock()
        val machine = ScooterHandshakeStateMachine(gate) { testScheduler.currentTime }
        val connection = ScooterConnectionImpl(ble, ScooterDevice("AA", "capture-verified"),
            backgroundScope, machine, gate, { testScheduler.currentTime },
            verifiedSpeed = NinebotRetailCodec::decodeSpeedKmh, lockProfileVerified = true)
        fun b0(raw: Int): ByteArray {
            val payload = ByteArray(32)
            payload[10] = raw.toByte()
            payload[11] = (raw ushr 8).toByte()
            return reply(0xb0, argument = 4, payload = payload, source = 0x23)
        }
        connection.start(); runCurrent()
        repeat(3) { ble.emit(b0(0)); runCurrent() }
        ble.emit(reply(0x5b, payload = byteArrayOf(1))); runCurrent()
        ble.emit(reply(0x5c, argument = 1)); runCurrent()
        ble.emit(reply(0x5d)); runCurrent()
        assertEquals(ConnectionState.Ready, connection.state.value)
        repeat(3) { ble.emit(b0(0)); runCurrent() }
        assertEquals(CommandOutcome.Success, connection.lock())
        val writeCount = ble.writes.size
        ble.emit(b0(0xff3e)); runCurrent()
        assertNull(connection.telemetry.value?.speedKmh)
        assertTrue(connection.unlock() is CommandOutcome.InvalidArgument)
        assertEquals(writeCount, ble.writes.size)
        connection.close()
    }

    @Test fun `unverified speed blocks pairing and forbidden controls never write`() = runTest {
        val ble = FakeTransport()
        val gate = MotionInterlock()
        val machine = ScooterHandshakeStateMachine(gate) { testScheduler.currentTime }
        val connection = ScooterConnectionImpl(ble, ScooterDevice("AA", "ES2"), backgroundScope,
            machine, gate, { testScheduler.currentTime })
        assertFalse(connection.lockSupported)
        connection.start(); runCurrent()
        ble.emit(reply(0x5b, payload = byteArrayOf(1))); runCurrent()
        assertTrue(connection.state.value is ConnectionState.Failed)
        assertEquals(1, ble.writes.size)
        assertTrue(connection.lock() is CommandOutcome.TransportError)
        connection.close()
    }
}
