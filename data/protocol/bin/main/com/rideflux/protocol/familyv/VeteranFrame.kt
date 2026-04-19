/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of RideFlux. It is licensed under the GNU General
 * Public License, version 3 or (at your option) any later version.
 * See the LICENSE file in the repository root for the full text.
 */
package com.rideflux.protocol.familyv

/**
 * Immutable telemetry model for Family V (Veteran — Sherman, Abrams,
 * Patton, Lynx, …) as specified in `PROTOCOL_SPEC.md` §3.3.
 *
 * All scalar fields are stored at their native wire resolution
 * (hundredths of the SI unit for voltage / current / temperature /
 * pitch / PWM, 0.1 km/h-equivalent for speed, metres for distance,
 * seconds for auto-off, ungrouped enums for charge / pedals modes).
 * Convenience accessors expose SI units and decoded strings.
 */
data class VeteranFrame(
    val payloadLength: Int,
    val voltageHundredthsV: Int,
    val speedTenthsKmh: Int,
    val tripMeters: Long,
    val totalMeters: Long,
    val phaseCurrentHundredthsA: Int,
    val temperatureHundredthsC: Int,
    val autoPowerOffSeconds: Int,
    val chargeMode: Int,
    val speedAlertTenthsKmh: Int,
    val speedTiltbackTenthsKmh: Int,
    val firmwareVersionRaw: Int,
    val pedalsMode: Int,
    val pitchAngleHundredthsDeg: Int,
    val hardwarePwmHundredthsPercent: Int,
    val crc32Present: Boolean,
) {

    val voltageVolts: Double get() = voltageHundredthsV / 100.0

    /** Speed in km/h, derived from the 0.1 km/h-equivalent raw field (§3.3). */
    val speedKmh: Double get() = speedTenthsKmh / 10.0

    val phaseCurrentAmps: Double get() = phaseCurrentHundredthsA / 100.0
    val temperatureCelsius: Double get() = temperatureHundredthsC / 100.0
    val speedAlertKmh: Double get() = speedAlertTenthsKmh / 10.0
    val speedTiltbackKmh: Double get() = speedTiltbackTenthsKmh / 10.0
    val pitchAngleDegrees: Double get() = pitchAngleHundredthsDeg / 100.0
    val hardwarePwmPercent: Double get() = hardwarePwmHundredthsPercent / 100.0

    /** Firmware-version string per §8.4 ("%03d.%d.%02d"). */
    val firmwareVersionString: String
        get() {
            val v = firmwareVersionRaw
            val major = v / 1000
            val minor = (v % 1000) / 100
            val patch = v % 100
            return String.format("%03d.%d.%02d", major, minor, patch)
        }

    /** Charge-mode enum (§4.2). */
    val chargeStatus: ChargeStatus
        get() = when (chargeMode) {
            0 -> ChargeStatus.IDLE
            1 -> ChargeStatus.CHARGING
            2 -> ChargeStatus.FULLY_CHARGED
            else -> ChargeStatus.RESERVED
        }

    enum class ChargeStatus { IDLE, CHARGING, FULLY_CHARGED, RESERVED }
}
