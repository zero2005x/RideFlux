package com.rideflux.protocol.familyscooter.ninebot

import com.rideflux.domain.safety.MotionInterlock

/**
 * Pre-encryption Ninebot pairing sequence. This is a pure state machine, not a BLE writer.
 * Feed [motionInterlock] only with model-scaled, freshly decoded speed samples. With an
 * unknown speed scale it stays closed and cannot emit the critical 0x5C/0x5D frames.
 */
class ScooterHandshakeStateMachine(
    private val motionInterlock: MotionInterlock,
    private val clockMillis: () -> Long,
) {
    sealed interface State {
        data object Unbonded : State
        data object RequestingBleRandom : State
        data class WaitingForUserConfirmation(val deadlineMillis: Long) : State
        data object AwaitingPairingAcceptance : State
        data object PairingComplete : State
        data object ReadyForTelemetry : State
    }

    var state: State = State.Unbonded
        private set

    fun begin(): ByteArray {
        check(state == State.Unbonded) { "Pairing has already started" }
        state = State.RequestingBleRandom
        return NinebotRetailCodec.buildHandshakeStep1()
    }

    /** The BLE random must arrive in a valid 0x5B reply frame. */
    fun onBleRandomReceived(replyFrame: ByteArray, appRandom: ByteArray): ByteArray {
        check(state == State.RequestingBleRandom) { "No BLE random request is pending" }
        val reply = NinebotRetailCodec.decodeFrame(replyFrame)
            ?: throw IllegalArgumentException("Invalid BLE-random reply")
        require(reply.command == 0x5b && reply.source == 0x04 &&
            reply.destination == 0x3e && reply.payload.isNotEmpty()) {
            "Expected a non-empty 0x5B BLE-random reply"
        }
        val now = clockMillis()
        val proposal = NinebotRetailCodec.buildHandshakeStep2(appRandom, motionInterlock, now)
        require(now <= Long.MAX_VALUE - CONFIRMATION_TIMEOUT_MILLIS)
        state = State.WaitingForUserConfirmation(now + CONFIRMATION_TIMEOUT_MILLIS)
        return proposal
    }

    /**
     * A proposal ACK alone is insufficient. The reply must be the 0x5C/0x01 user-confirmation
     * event within the 20-second window. The 0x5D payload is supplied by a verified profile.
     */
    fun onUserConfirmed(confirmationFrame: ByteArray, confirmedPayload: ByteArray): ByteArray {
        val waiting = state as? State.WaitingForUserConfirmation
            ?: error("Not awaiting user confirmation")
        val now = clockMillis()
        if (now >= waiting.deadlineMillis) {
            state = State.Unbonded
            throw IllegalStateException("Power-button confirmation timed out")
        }
        val event = NinebotRetailCodec.decodeFrame(confirmationFrame)
            ?: throw IllegalArgumentException("Invalid confirmation frame")
        require(event.command == 0x5c && event.argument == 0x01 &&
            event.source == 0x04 && event.destination == 0x3e) {
            "A 0x5C proposal ACK is not power-button confirmation"
        }
        val accept = NinebotRetailCodec.buildHandshakeStep3(
            confirmedPayload, motionInterlock, now)
        state = State.AwaitingPairingAcceptance
        return accept
    }

    /** The final response must at least be a valid 0x5D frame from the dashboard. */
    fun onPairingAccepted(replyFrame: ByteArray) {
        check(state == State.AwaitingPairingAcceptance)
        val reply = NinebotRetailCodec.decodeFrame(replyFrame)
            ?: throw IllegalArgumentException("Invalid pairing acceptance frame")
        require(reply.command == 0x5d && reply.source == 0x04 && reply.destination == 0x3e) {
            "Expected 0x5D acceptance from dashboard"
        }
        state = State.PairingComplete
    }

    fun markReadyForTelemetry() {
        check(state == State.PairingComplete)
        state = State.ReadyForTelemetry
    }

    fun pollTimeout(): Boolean {
        val waiting = state as? State.WaitingForUserConfirmation ?: return false
        if (clockMillis() < waiting.deadlineMillis) return false
        state = State.Unbonded
        return true
    }

    fun reset() {
        state = State.Unbonded
        motionInterlock.reset()
    }

    companion object { const val CONFIRMATION_TIMEOUT_MILLIS = 20_000L }
}
