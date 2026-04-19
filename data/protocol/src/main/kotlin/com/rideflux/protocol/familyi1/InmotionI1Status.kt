/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi1

import com.rideflux.protocol.bytes.ByteReader

/**
 * Decoder for the Family I1 state word at telemetry EX-DATA offset 60
 * (`PROTOCOL_SPEC.md` §4.3.1).
 *
 * The 32-bit little-endian state word carried in
 * [InmotionI1ExtendedTelemetry.stateWordRaw] is interpreted under one
 * of two enumerations, selected by model family (§3.5.3.1):
 *
 * - [Convention.LEGACY] masks `state & 0x0F`;
 * - [Convention.MODERN] reads the *low-byte* high nibble
 *   `(state >>> 4) & 0x0F` and appends `" - Engine off"` whenever
 *   `state & 0x0F == 1`.
 *
 * The caller is responsible for picking the right convention from the
 * model-id tables in §3.5.3.1.
 */
object InmotionI1StateWord {

    /** Which §4.3.1 table to apply to the state word. */
    enum class Convention { LEGACY, MODERN }

    /** Parsed representation of the state word. */
    sealed class Decoded {
        /** [displayString] is the human-readable mode, as defined in §4.3.1. */
        abstract val displayString: String

        data class Legacy(val code: Int, val mode: String) : Decoded() {
            override val displayString: String get() = mode
        }

        data class Modern(
            val highNibble: Int,
            val primary: String,
            val engineOff: Boolean,
        ) : Decoded() {
            override val displayString: String
                get() = if (engineOff) "$primary - Engine off" else primary
        }
    }

    /** Apply [convention] to the raw 32-bit little-endian state word [stateWord]. */
    fun decode(stateWord: Long, convention: Convention): Decoded = when (convention) {
        Convention.LEGACY -> decodeLegacy(stateWord)
        Convention.MODERN -> decodeModern(stateWord)
    }

    /** §4.3.1 legacy table (R1 / R2 / R0 / V3 / V5 / L6 / Lively / V8 / V10S / V10SF). */
    fun decodeLegacy(stateWord: Long): Decoded.Legacy {
        val code = (stateWord and 0x0FL).toInt()
        val mode = when (code) {
            0 -> "Idle"
            1 -> "Drive"
            2 -> "Zero"
            3 -> "LargeAngle"
            4 -> "Check"
            5 -> "Lock"
            6 -> "Error"
            7 -> "Carry"
            8 -> "RemoteControl"
            9 -> "Shutdown"
            10 -> "pomStop"
            12 -> "Unlock"
            else -> "Unknown"
        }
        return Decoded.Legacy(code, mode)
    }

    /** §4.3.1 modern table (V8F / V8S / V10 / V10F / V10FT / V10T). */
    fun decodeModern(stateWord: Long): Decoded.Modern {
        val high = ((stateWord ushr 4) and 0x0FL).toInt()
        val primary = when (high) {
            1 -> "Shutdown"
            2 -> "Drive"
            3 -> "Charging"
            else -> "Unknown code $high"
        }
        val engineOff = (stateWord and 0x0FL).toInt() == 1
        return Decoded.Modern(high, primary, engineOff)
    }
}

/**
 * Family I1 asynchronous alert record carried in the 8-byte DATA-8 of
 * a standard frame with CAN-ID `0x0F780101` (`PROTOCOL_SPEC.md`
 * §4.3.2).
 *
 * **Byte order exception.** Unlike the CAN-envelope telemetry fields
 * which are little-endian per §2.6.3, the alert record's [aValue1]
 * and [aValue2] quantities are **big-endian** two's-complement
 * integers per §4.3.2.
 *
 * @property alertId U8 alert code (see [Event.of]).
 * @property aValue1 S16BE quantity 1 at DATA-8 offset 2..3.
 * @property aValue2 S32BE quantity 2 at DATA-8 offset 4..7.
 */
data class InmotionI1AlertRecord(
    val alertId: Int,
    val aValue1: Int,
    val aValue2: Int,
    val rawData8: ByteArray,
) {

    /**
     * Derived human-readable speed in km/h, per §4.3.2:
     * `|aValue2 / 3812| × 3.6`. The constant `3812` is the default
     * I1 calibration constant and is **not** reconciled with the
     * per-model value from §3.5.2.
     */
    val aSpeedKmh: Double get() = kotlin.math.abs(aValue2 / 3812.0) * 3.6

    /** High-level event decoded from [alertId] per the §4.3.2 table. */
    val event: Event get() = Event.of(this)

    /** Semantic event forms keyed off [alertId]. */
    sealed class Event {
        /** `alertId = 0x05`. */
        data class StartFromTiltAngle(val tiltDegrees: Double, val speedKmh: Double) : Event()
        /** `alertId = 0x06`. */
        data class TiltBack(val speedKmh: Double, val limit: Double) : Event()
        /** `alertId = 0x19`. */
        data object FallDownDetected : Event()
        /** `alertId = 0x1D`. */
        data class BadBatteryCell(val voltageV: Double) : Event()
        /** `alertId = 0x20`. */
        data class LowBattery(val voltageV: Double) : Event()
        /** `alertId = 0x21`. */
        data class SpeedCutoff(val speedKmh: Double, val aux: Double) : Event()
        /** `alertId = 0x26`. */
        data class HighLoad(val speedKmh: Double, val currentA: Double) : Event()
        /** Any reserved or unrecognised `alertId`. */
        data class Unknown(val alertId: Int, val rawData8: ByteArray) : Event() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Unknown) return false
                return alertId == other.alertId && rawData8.contentEquals(other.rawData8)
            }
            override fun hashCode(): Int = 31 * alertId + rawData8.contentHashCode()
        }

        companion object {
            fun of(a: InmotionI1AlertRecord): Event = when (a.alertId) {
                0x05 -> StartFromTiltAngle(a.aValue1 / 100.0, a.aSpeedKmh)
                0x06 -> TiltBack(a.aSpeedKmh, a.aValue1 / 1000.0)
                0x19 -> FallDownDetected
                0x1D -> BadBatteryCell(a.aValue2 / 100.0)
                0x20 -> LowBattery(a.aValue2 / 100.0)
                0x21 -> SpeedCutoff(a.aSpeedKmh, a.aValue1 / 10.0)
                0x26 -> HighLoad(a.aSpeedKmh, a.aValue1 / 1000.0)
                else -> Unknown(a.alertId, a.rawData8.copyOf())
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InmotionI1AlertRecord) return false
        return alertId == other.alertId &&
            aValue1 == other.aValue1 &&
            aValue2 == other.aValue2 &&
            rawData8.contentEquals(other.rawData8)
    }

    override fun hashCode(): Int {
        var h = alertId
        h = 31 * h + aValue1
        h = 31 * h + aValue2
        h = 31 * h + rawData8.contentHashCode()
        return h
    }

    companion object {
        /** Parse [data8] (exactly 8 bytes, the DATA-8 field of the frame). */
        fun parse(data8: ByteArray): InmotionI1AlertRecord {
            require(data8.size == 8) { "DATA-8 must be 8 bytes, got ${data8.size}" }
            val alertId = data8[0].toInt() and 0xFF
            val aValue1 = ByteReader.s16BE(data8, 2)
            // §4.3.2 aValue2 is signed 32-bit big-endian.
            // ByteReader.u32BE returns Long; .toInt() yields S32BE via
            // two's-complement truncation.
            val aValue2 = ByteReader.u32BE(data8, 4).toInt()
            return InmotionI1AlertRecord(
                alertId = alertId,
                aValue1 = aValue1,
                aValue2 = aValue2,
                rawData8 = data8.copyOf(),
            )
        }
    }
}
