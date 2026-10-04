package com.rideflux.protocol.familyscooter

import com.rideflux.domain.safety.MotionInterlock
import com.rideflux.protocol.familyscooter.ninebot.NinebotRetailCodec
import com.rideflux.protocol.familyscooter.ninebot.ScooterHandshakeStateMachine
import com.rideflux.protocol.testutil.hex
import org.junit.Assert.*
import org.junit.Test

class ScooterHandshakeStateMachineTest {
    @Test fun `pairing waits for button confirmation and a fresh stationary speed source`() {
        var now = 1_000L
        val gate = MotionInterlock()
        val machine = ScooterHandshakeStateMachine(gate) { now }
        assertArrayEquals(hex("5A A5 00 3E 04 5B 00 62 FF"), machine.begin())
        assertEquals(ScooterHandshakeStateMachine.State.RequestingBleRandom, machine.state)
        assertThrows(IllegalArgumentException::class.java) {
            machine.onBleRandomReceived(hex("5A A5 01 04 3E 5B 00 01 00 FF"), ByteArray(16))
        }
        assertThrows(SecurityException::class.java) {
            machine.onBleRandomReceived(hex("5A A5 01 04 3E 5B 00 01 60 FF"), ByteArray(16))
        }
        assertEquals(ScooterHandshakeStateMachine.State.RequestingBleRandom, machine.state)

        repeat(3) { gate.observe(0f, 800 + it * 100L) }
        val proposal = machine.onBleRandomReceived(hex("5A A5 01 04 3E 5B 00 01 60 FF"),
            ByteArray(16) { it.toByte() })
        assertEquals(0x5c, NinebotRetailCodec.decodeFrame(proposal)?.command)
        assertTrue(machine.state is ScooterHandshakeStateMachine.State.WaitingForUserConfirmation)

        // 0x5C/0x00 is only a proposal ACK. It must not advance to 0x5D.
        assertThrows(IllegalArgumentException::class.java) {
            machine.onUserConfirmed(hex("5A A5 00 04 3E 5C 00 61 FF"), byteArrayOf(1))
        }
        now = 5_000L
        assertThrows(SecurityException::class.java) {
            machine.onUserConfirmed(hex("5A A5 00 04 3E 5C 01 60 FF"), byteArrayOf(1))
        }
        repeat(3) { gate.observe(0f, 4_800 + it * 100L) }
        val accept = machine.onUserConfirmed(hex("5A A5 00 04 3E 5C 01 60 FF"),
            byteArrayOf(1))
        assertEquals(0x5d, NinebotRetailCodec.decodeFrame(accept)?.command)
        assertEquals(ScooterHandshakeStateMachine.State.AwaitingPairingAcceptance, machine.state)
        assertThrows(IllegalArgumentException::class.java) {
            machine.onPairingAccepted(hex("5A A5 00 04 3E 5C 01 60 FF"))
        }
        machine.onPairingAccepted(hex("5A A5 00 04 3E 5D 00 60 FF"))
        assertEquals(ScooterHandshakeStateMachine.State.PairingComplete, machine.state)
        machine.markReadyForTelemetry()
        assertEquals(ScooterHandshakeStateMachine.State.ReadyForTelemetry, machine.state)
    }

    @Test fun `confirmation expires at twenty seconds without retransmission`() {
        var now = 1_000L
        val gate = MotionInterlock()
        val machine = ScooterHandshakeStateMachine(gate) { now }
        machine.begin()
        repeat(3) { gate.observe(0f, 800 + it * 100L) }
        machine.onBleRandomReceived(hex("5A A5 01 04 3E 5B 00 01 60 FF"), ByteArray(16))
        now = 20_999L
        assertFalse(machine.pollTimeout())
        now = 21_000L
        assertTrue(machine.pollTimeout())
        assertEquals(ScooterHandshakeStateMachine.State.Unbonded, machine.state)
        assertThrows(IllegalStateException::class.java) {
            machine.onPairingAccepted(hex("5A A5 00 04 3E 5D 00 60 FF"))
        }
    }
}
