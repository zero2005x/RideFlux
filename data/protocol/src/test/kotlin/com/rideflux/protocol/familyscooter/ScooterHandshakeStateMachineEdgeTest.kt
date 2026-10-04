package com.rideflux.protocol.familyscooter

import com.rideflux.domain.safety.MotionInterlock
import com.rideflux.protocol.familyscooter.ninebot.NinebotRetailCodec
import com.rideflux.protocol.familyscooter.ninebot.ScooterHandshakeStateMachine
import com.rideflux.protocol.familyscooter.ninebot.ScooterHandshakeStateMachine.State
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScooterHandshakeStateMachineEdgeTest {
    private var now = 1_000L
    private val gate = MotionInterlock().also { g -> repeat(3) { g.observe(0f, 900L + it * 50L) } }
    private val machine = ScooterHandshakeStateMachine(gate) { now }

    private fun reply(command: Int, argument: Int = 0, source: Int = 0x04, destination: Int = 0x3e,
                      payload: ByteArray = byteArrayOf(1)): ByteArray {
        val body = byteArrayOf(payload.size.toByte(), source.toByte(), destination.toByte(),
            command.toByte(), argument.toByte()) + payload
        return RetailFraming.appendChecksum(byteArrayOf(0x5a, 0xa5.toByte()) + body, body)
    }

    private fun toWaiting() {
        machine.begin()
        machine.onBleRandomReceived(reply(0x5b), ByteArray(16) { it.toByte() })
    }

    private fun refreshGate() = repeat(3) { gate.observe(0f, now - 100L + it * 10L) }

    @Test fun `calls in the wrong state are rejected`() {
        assertThrows(IllegalStateException::class.java) { machine.onBleRandomReceived(reply(0x5b), ByteArray(16)) }
        assertThrows(IllegalStateException::class.java) { machine.onUserConfirmed(reply(0x5c, 1)) }
        assertThrows(IllegalStateException::class.java) { machine.onPairingAccepted(reply(0x5d)) }
        assertThrows(IllegalStateException::class.java) { machine.markReadyForTelemetry() }
        assertFalse(machine.pollTimeout())
        machine.begin()
        assertThrows(IllegalStateException::class.java) { machine.begin() }
        assertFalse(machine.pollTimeout())
    }

    @Test fun `BLE random reply must come from the dashboard and carry data`() {
        machine.begin()
        val random = ByteArray(16)
        assertThrows(IllegalArgumentException::class.java) { machine.onBleRandomReceived(reply(0x5c), random) }
        assertThrows(IllegalArgumentException::class.java) { machine.onBleRandomReceived(reply(0x5b, source = 0x20), random) }
        assertThrows(IllegalArgumentException::class.java) { machine.onBleRandomReceived(reply(0x5b, destination = 0x20), random) }
        assertThrows(IllegalArgumentException::class.java) {
            machine.onBleRandomReceived(reply(0x5b, payload = ByteArray(0)), random)
        }
        assertThrows(IllegalArgumentException::class.java) { machine.onBleRandomReceived(ByteArray(3), random) }
        assertEquals(State.RequestingBleRandom, machine.state)
    }

    @Test fun `clock near overflow is refused`() {
        val gateAtMax = MotionInterlock().also { g -> repeat(3) { g.observe(0f, Long.MAX_VALUE - 2 + it) } }
        val m = ScooterHandshakeStateMachine(gateAtMax) { Long.MAX_VALUE }
        m.begin()
        assertThrows(IllegalArgumentException::class.java) { m.onBleRandomReceived(reply(0x5b), ByteArray(16)) }
    }

    @Test fun `confirmation must be the button event inside the window`() {
        toWaiting()
        assertThrows(IllegalArgumentException::class.java) { machine.onUserConfirmed(ByteArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { machine.onUserConfirmed(reply(0x5d, 1)) }
        assertThrows(IllegalArgumentException::class.java) { machine.onUserConfirmed(reply(0x5c, 0)) }
        assertThrows(IllegalArgumentException::class.java) { machine.onUserConfirmed(reply(0x5c, 1, source = 0x20)) }
        assertThrows(IllegalArgumentException::class.java) { machine.onUserConfirmed(reply(0x5c, 1, destination = 0x20)) }
        now = 21_000L
        val late = assertThrows(IllegalStateException::class.java) { machine.onUserConfirmed(reply(0x5c, 1)) }
        assertTrue(late.message!!.contains("timed out"))
        assertEquals(State.Unbonded, machine.state)
    }

    @Test fun `an explicit payload overrides the proposed random`() {
        toWaiting()
        now = 5_000L
        refreshGate()
        val custom = ByteArray(16) { 0x40 }
        val accept = machine.onUserConfirmed(reply(0x5c, 1), custom)
        assertArrayEquals(custom, NinebotRetailCodec.decodeFrame(accept)!!.payload)
    }

    @Test fun `acceptance must be a dashboard 0x5D frame`() {
        toWaiting()
        now = 5_000L
        refreshGate()
        machine.onUserConfirmed(reply(0x5c, 1))
        assertThrows(IllegalArgumentException::class.java) { machine.onPairingAccepted(ByteArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { machine.onPairingAccepted(reply(0x5d, source = 0x20)) }
        assertThrows(IllegalArgumentException::class.java) { machine.onPairingAccepted(reply(0x5d, destination = 0x20)) }
        assertEquals(State.AwaitingPairingAcceptance, machine.state)
    }

    @Test fun `reset returns to unbonded and revokes the stationary permit`() {
        toWaiting()
        machine.reset()
        assertEquals(State.Unbonded, machine.state)
        assertThrows(SecurityException::class.java) { gate.requireAllowed(com.rideflux.domain.safety.DangerTier.CRITICAL, now) }
        machine.begin()
        assertEquals(State.RequestingBleRandom, machine.state)
    }
}
