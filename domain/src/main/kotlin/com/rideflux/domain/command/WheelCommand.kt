/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.command

/**
 * Typed, family-agnostic command issued to a
 * [com.rideflux.domain.connection.WheelConnection].
 *
 * Feature modules may construct commands directly and dispatch via
 * [com.rideflux.domain.connection.WheelConnection.dispatch], or use
 * the convenience suspend methods on `WheelConnection` which wrap
 * the same types internally. The sealed hierarchy exists so that
 * command queueing, logging and replay are all type-safe.
 *
 * Not every family supports every command. Before dispatching,
 * callers should consult
 * [com.rideflux.domain.wheel.WheelCapabilities]; commands whose
 * capability flag is `false` fail fast with
 * [CommandOutcome.Unsupported].
 *
 * Note on validation: constructing a command may throw
 * [IllegalArgumentException] for out-of-range parameters (e.g. a
 * negative speed or an invalid PIN). Commands that pass construction
 * can still fail at dispatch time with
 * [CommandOutcome.InvalidArgument] if the family implementation
 * rejects the value, so callers should handle both paths.
 */
sealed class WheelCommand {

    // ---- Lighting -------------------------------------------------------

    data class SetHeadlight(val on: Boolean) : WheelCommand()
    data class SetLedStrip(val on: Boolean) : WheelCommand()
    data class SetDecorativeLights(val on: Boolean) : WheelCommand()

    // ---- Audio ----------------------------------------------------------

    data object Beep : WheelCommand()
    data object Horn : WheelCommand()
    data class SetVolume(val percent: Int) : WheelCommand() {
        init { require(percent in 0..100) { "percent must be 0..100" } }
    }
    data class PlaySound(val soundId: Int) : WheelCommand()

    // ---- Ride configuration --------------------------------------------

    data class SetMaxSpeedKmh(val kmh: Float) : WheelCommand() {
        // isFinite() rather than `>= 0f`: NaN is already rejected by the
        // comparison, but POSITIVE_INFINITY is not, and an infinite speed
        // silently saturates/overflows every family's wire encoder.
        init {
            require(kmh.isFinite() && kmh in 0f..MAX_SPEED_LIMIT_KMH) {
                "kmh must be finite and within 0..$MAX_SPEED_LIMIT_KMH"
            }
        }
    }

    data class SetTiltbackKmh(val kmh: Float) : WheelCommand() {
        init {
            require(kmh.isFinite() && kmh in 0f..MAX_SPEED_LIMIT_KMH) {
                "kmh must be finite and within 0..$MAX_SPEED_LIMIT_KMH"
            }
        }
    }

    data class SetPedalSensitivity(val level: Int) : WheelCommand()

    data class SetPedalHorizontal(val angleDegrees: Float) : WheelCommand() {
        init {
            require(angleDegrees.isFinite()) { "angleDegrees must be finite" }
        }
    }

    data class SetRideMode(val modeCode: Int) : WheelCommand()

    // ---- Calibration / power -------------------------------------------

    data object Calibrate : WheelCommand()

    /** Two-stage power-off is handled internally by the codec where needed. */
    data object PowerOff : WheelCommand()

    // ---- Authentication -------------------------------------------------

    data class UnlockWithPin(val pin: String) : WheelCommand() {
        init {
            require(pin.all(Char::isDigit)) { "pin must be numeric" }
            require(pin.length in 4..8) { "pin length must be 4..8 digits" }
        }

        // Never print the PIN through the auto-generated toString() —
        // outcome wrappers (Unsupported/InvalidArgument/TransportError)
        // retain the command and can end up in logs.
        override fun toString(): String = "UnlockWithPin(pin=***)"
    }

    // ---- Escape hatch ---------------------------------------------------

    /**
     * Raw family-specific command bytes. Feature code should avoid
     * this; it exists only to let advanced / diagnostic tooling
     * send bespoke frames without extending the sealed hierarchy.
     */
    data class Raw(val bytes: ByteArray) : WheelCommand() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Raw) return false
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    companion object {
        /**
         * Upper bound accepted for any speed-valued command.
         *
         * No supported wheel family goes anywhere near this, so the
         * bound exists purely to reject obviously-bogus input (and
         * infinities) before it reaches a wire encoder that would
         * saturate or wrap it into a plausible-looking frame.
         */
        const val MAX_SPEED_LIMIT_KMH: Float = 200f
    }
}

/**
 * Result of dispatching a [WheelCommand].
 *
 * A [Success] indicates that the command was serialised and handed
 * to the BLE transport; it does **not** guarantee the device acted
 * on it. Feature code that needs a stronger guarantee should observe
 * [com.rideflux.domain.connection.WheelConnection.telemetry] for the
 * expected state change and time-out after a reasonable interval.
 */
sealed class CommandOutcome {
    data object Success : CommandOutcome()

    /** The current family does not implement this command. */
    data class Unsupported(val command: WheelCommand) : CommandOutcome()

    /** Parameters were out of range for the detected model. */
    data class InvalidArgument(val command: WheelCommand, val message: String) : CommandOutcome()

    /** BLE write failed or connection was not [com.rideflux.domain.connection.ConnectionState.Ready]. */
    data class TransportError(val command: WheelCommand, val cause: Throwable?) : CommandOutcome()
}
