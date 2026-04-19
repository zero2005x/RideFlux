/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi2

/**
 * Decoders for the Family I2 state bytes and the 7-byte error bitmap
 * (`PROTOCOL_SPEC.md` §4.3.3 and §4.3.4).
 *
 * These are standalone parsers because the location of the state
 * bytes and the error bitmap inside the real-time telemetry frame is
 * variant-dependent (§8.8 per-model offset matrix). Callers resolve
 * the correct offsets from the car-type identification record of
 * §3.5.2 and pass the raw bytes here.
 */

// ----- §4.3.3 state byte A ------------------------------------------------

/**
 * Parsed view of Family I2 telemetry state byte A (§4.3.3). The
 * bit layout is:
 *
 * | Bits  | Width | Meaning                                          |
 * |------:|:-----:|--------------------------------------------------|
 * | 0..2  | 3     | PC-mode (`0` Lock, `1` Drive, `2` Shutdown, `3` Idle) |
 * | 3..5  | 3     | MC-mode (internal motor-controller sub-state)    |
 * | 6     | 1     | Motor active                                     |
 * | 7     | 1     | Charging                                         |
 */
@JvmInline
value class InmotionI2StateA(val raw: Int) {
    init {
        // value class init blocks run on construction.
        require(raw in 0..0xFF) { "stateA byte must be in 0..255, got $raw" }
    }

    val pcModeCode: Int get() = raw and 0x07

    val pcMode: String
        get() = when (pcModeCode) {
            0 -> "Lock"
            1 -> "Drive"
            2 -> "Shutdown"
            3 -> "Idle"
            else -> "Reserved$pcModeCode"
        }

    val mcModeCode: Int get() = (raw ushr 3) and 0x07

    val motorActive: Boolean get() = (raw and 0x40) != 0

    val charging: Boolean get() = (raw and 0x80) != 0
}

// ----- §4.3.3 state byte B ------------------------------------------------

/**
 * Parsed view of Family I2 telemetry state byte B (§4.3.3). The bit
 * layout is identical across variants, with a per-variant alias on
 * bit 5 (`coolingFanOrDfu`): V11-early reports cooling-fan active,
 * V11 ≥ 1.4 and newer report firmware-update in progress.
 */
@JvmInline
value class InmotionI2StateB(val raw: Int) {
    init {
        require(raw in 0..0xFF) { "stateB byte must be in 0..255, got $raw" }
    }

    /** Headlight on (V11 early) / low-beam on (V11 ≥ 1.4 and newer). */
    val headlightOn: Boolean get() = (raw and 0x01) != 0

    /** Decorative light on (V11 early) / high-beam on (V11 ≥ 1.4 and newer). */
    val decorativeLightOn: Boolean get() = (raw and 0x02) != 0

    /** Rider lifted foot off pedal. */
    val lifted: Boolean get() = (raw and 0x04) != 0

    /** 2-bit tail-light mode. */
    val tailLightMode: Int get() = (raw ushr 3) and 0x03

    /** Cooling-fan active (V11 early) / firmware-update in progress (V11 ≥ 1.4 and newer). */
    val coolingFanOrDfu: Boolean get() = (raw and 0x20) != 0
}

/**
 * Produce the §4.3.3 convenience status string by concatenating the
 * tokens `"Active"` (state-A bit 6), `" Charging"` (state-A bit 7)
 * and `" Lifted"` (state-B bit 2) in that order. Absent flags
 * contribute nothing.
 */
fun inmotionI2StatusString(a: InmotionI2StateA, b: InmotionI2StateB): String {
    val s = StringBuilder()
    if (a.motorActive) s.append("Active")
    if (a.charging) s.append(" Charging")
    if (b.lifted) s.append(" Lifted")
    return s.toString()
}

// ----- §4.3.4 error bitmap ------------------------------------------------

/**
 * Severity code for the two 2-bit severity fields in `E3`
 * (`over_bus_current` at bits 2..3, `low_battery` at bits 4..5).
 * Semantics per §4.3.4: `0` no fault, `1` informational, `2`
 * warning, `3` critical.
 */
enum class InmotionI2Severity(val code: Int) {
    NONE(0),
    INFORMATIONAL(1),
    WARNING(2),
    CRITICAL(3);

    companion object {
        fun of(code: Int): InmotionI2Severity {
            require(code in 0..3) { "severity must be 0..3, got $code" }
            return entries[code]
        }
    }
}

/**
 * Family I2 56-bit error bitmap spanning 7 consecutive bytes
 * `E0..E6` (§4.3.4).
 *
 * The starting offset of this block inside the real-time telemetry
 * DATA region is variant-dependent; resolve it via the §8.8 offset
 * matrix and pass the slice or whole DATA + offset here.
 */
data class InmotionI2ErrorBitmap(
    val e0: Int,
    val e1: Int,
    val e2: Int,
    val e3: Int,
    val e4: Int,
    val e5: Int,
    val e6: Int,
) {
    init {
        require(e0 in 0..0xFF && e1 in 0..0xFF && e2 in 0..0xFF &&
            e3 in 0..0xFF && e4 in 0..0xFF && e5 in 0..0xFF && e6 in 0..0xFF
        ) { "error bitmap bytes must each be 0..255" }
    }

    // --- E0 ---------------------------------------------------------------

    val phaseCurrentSensor: Boolean get() = (e0 and 0x01) != 0
    val busCurrentSensor: Boolean get() = (e0 and 0x02) != 0
    val motorHall: Boolean get() = (e0 and 0x04) != 0
    val battery: Boolean get() = (e0 and 0x08) != 0
    val imuSensor: Boolean get() = (e0 and 0x10) != 0
    val controllerCom1: Boolean get() = (e0 and 0x20) != 0
    val controllerCom2: Boolean get() = (e0 and 0x40) != 0
    val bleCom1: Boolean get() = (e0 and 0x80) != 0

    // --- E1 ---------------------------------------------------------------

    val bleCom2: Boolean get() = (e1 and 0x01) != 0
    val mosTempSensor: Boolean get() = (e1 and 0x02) != 0
    val motorTempSensor: Boolean get() = (e1 and 0x04) != 0
    val batteryTempSensor: Boolean get() = (e1 and 0x08) != 0
    val boardTempSensor: Boolean get() = (e1 and 0x10) != 0
    val fan: Boolean get() = (e1 and 0x20) != 0
    val rtc: Boolean get() = (e1 and 0x40) != 0
    val externalRom: Boolean get() = (e1 and 0x80) != 0

    // --- E2 ---------------------------------------------------------------

    val vbusSensor: Boolean get() = (e2 and 0x01) != 0
    val vbatterySensor: Boolean get() = (e2 and 0x02) != 0
    val cannotPowerOff: Boolean get() = (e2 and 0x04) != 0
    val reservedE2_3: Boolean get() = (e2 and 0x08) != 0

    // --- E3 (two 2-bit severity fields mixed with single-bit flags) ------

    val underVoltage: Boolean get() = (e3 and 0x01) != 0
    val overVoltage: Boolean get() = (e3 and 0x02) != 0
    val overBusCurrentSeverity: InmotionI2Severity
        get() = InmotionI2Severity.of((e3 ushr 2) and 0x03)
    val lowBatterySeverity: InmotionI2Severity
        get() = InmotionI2Severity.of((e3 ushr 4) and 0x03)
    val mosTemp: Boolean get() = (e3 and 0x40) != 0
    val motorTemp: Boolean get() = (e3 and 0x80) != 0

    // --- E4 ---------------------------------------------------------------

    val batteryTemp: Boolean get() = (e4 and 0x01) != 0
    val overBoardTemp: Boolean get() = (e4 and 0x02) != 0
    val overSpeed: Boolean get() = (e4 and 0x04) != 0
    val outputSaturation: Boolean get() = (e4 and 0x08) != 0
    val motorSpin: Boolean get() = (e4 and 0x10) != 0
    val motorBlock: Boolean get() = (e4 and 0x20) != 0
    val posture: Boolean get() = (e4 and 0x40) != 0
    val riskBehaviour: Boolean get() = (e4 and 0x80) != 0

    // --- E5 ---------------------------------------------------------------

    val motorNoLoad: Boolean get() = (e5 and 0x01) != 0
    val noSelfTest: Boolean get() = (e5 and 0x02) != 0
    val compatibility: Boolean get() = (e5 and 0x04) != 0
    val powerKeyLongPress: Boolean get() = (e5 and 0x08) != 0
    val forceDfu: Boolean get() = (e5 and 0x10) != 0
    val deviceLock: Boolean get() = (e5 and 0x20) != 0
    val cpuOverTemp: Boolean get() = (e5 and 0x40) != 0
    val imuOverTemp: Boolean get() = (e5 and 0x80) != 0

    // --- E6 (bits 4..7 reserved) -----------------------------------------

    val reservedE6_0: Boolean get() = (e6 and 0x01) != 0
    val hwCompatibility: Boolean get() = (e6 and 0x02) != 0
    val fanLowSpeed: Boolean get() = (e6 and 0x04) != 0
    val reservedE6_3: Boolean get() = (e6 and 0x08) != 0

    /** `true` iff any bit in `E0..E6` is set. */
    val hasAnyFault: Boolean
        get() = (e0 or e1 or e2 or e3 or e4 or e5 or e6) != 0

    companion object {
        /** Number of bytes consumed by a single error bitmap (`E0..E6`). */
        const val SIZE: Int = 7

        /**
         * Parse [SIZE] bytes starting at [offset] into [data] as the
         * `E0..E6` block.
         */
        fun parse(data: ByteArray, offset: Int = 0): InmotionI2ErrorBitmap {
            require(offset >= 0 && data.size - offset >= SIZE) {
                "need $SIZE bytes at offset $offset, have ${data.size - offset}"
            }
            return InmotionI2ErrorBitmap(
                e0 = data[offset].toInt() and 0xFF,
                e1 = data[offset + 1].toInt() and 0xFF,
                e2 = data[offset + 2].toInt() and 0xFF,
                e3 = data[offset + 3].toInt() and 0xFF,
                e4 = data[offset + 4].toInt() and 0xFF,
                e5 = data[offset + 5].toInt() and 0xFF,
                e6 = data[offset + 6].toInt() and 0xFF,
            )
        }
    }
}
