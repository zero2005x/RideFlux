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

import java.util.Locale

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
    /** The length byte at offset 3: the frame is this many bytes plus the 4-byte header. */
    val declaredLength: Int,
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
    val protocolProfile: VeteranProtocolProfile = VeteranProtocolProfile.LEGACY,
    val rawByte28: Int = 0,
    val rawByte29: Int = 0,
    val rawByte30: Int = 0,
) {

    val voltageVolts: Double get() = voltageHundredthsV / 100.0

    /** Speed in km/h, derived from the 0.1 km/h-equivalent raw field (§3.3). */
    val speedKmh: Double get() = speedTenthsKmh / 10.0

    /**
     * Motor phase current in Amperes.
     * Under MODERN_NOSFET, scaled by 10.0.
     * Under LEGACY, scaled by 100.0.
     * Under UNKNOWN, scaling is ambiguous and remains null to avoid corrupting telemetry.
     */
    val phaseCurrentAmps: Double?
        get() = when (protocolProfile) {
            VeteranProtocolProfile.MODERN_NOSFET -> phaseCurrentHundredthsA / 10.0
            VeteranProtocolProfile.LEGACY -> phaseCurrentHundredthsA / 100.0
            VeteranProtocolProfile.UNKNOWN -> null
        }

    val temperatureCelsius: Double get() = temperatureHundredthsC / 100.0
    val speedAlertKmh: Double get() = speedAlertTenthsKmh / 10.0
    val speedTiltbackKmh: Double get() = speedTiltbackTenthsKmh / 10.0
    val pitchAngleDegrees: Double get() = pitchAngleHundredthsDeg / 100.0

    /**
     * Hardware PWM percentage.
     * Modern Nosfet uses offset 34 as an internal output multiplier in software
     * pack current calculation, NOT proven PWM.
     * Under UNKNOWN, offset 34 meaning is unconfirmed.
     * Non-null only under confirmed LEGACY profile.
     */
    val hardwarePwmPercent: Double?
        get() = when (protocolProfile) {
            VeteranProtocolProfile.LEGACY -> hardwarePwmHundredthsPercent / 100.0
            VeteranProtocolProfile.MODERN_NOSFET,
            VeteranProtocolProfile.UNKNOWN -> null
        }

    /** NOSFET calls offset 34 output, a multiplier in its current calculation; it is not proven PWM. */
    val outputRaw: Int? get() = hardwarePwmHundredthsPercent.takeIf {
        protocolProfile == VeteranProtocolProfile.MODERN_NOSFET
    }

    val modernHardwareKeyCandidate: String
        get() = VeteranModelRegistry.modernHardwareKey(rawByte30, rawByte28, rawByte29)

    val legacyHardwareKeyCandidate: String
        get() = ((rawByte28 shl 8) or rawByte29).toString().padStart(6, '0').take(4)

    val hardwareKey: String?
        get() = when (protocolProfile) {
            VeteranProtocolProfile.MODERN_NOSFET -> modernHardwareKeyCandidate
            VeteranProtocolProfile.LEGACY -> legacyHardwareKeyCandidate
            VeteranProtocolProfile.UNKNOWN -> null
        }

    /** Firmware-version string per §8.4 ("%03d.%d.%02d"). Null under UNKNOWN profile. */
    val firmwareVersionString: String?
        get() = when (protocolProfile) {
            VeteranProtocolProfile.UNKNOWN -> null
            else -> {
                val v = firmwareVersionRaw
                val major = v / 1000
                val minor = (v % 1000) / 100
                val patch = v % 100
                String.format(Locale.ROOT, "%03d.%d.%02d", major, minor, patch)
            }
        }

    /** Charge-mode enum (§4.2). Null under UNKNOWN profile. */
    val chargeStatus: ChargeStatus?
        get() = when (protocolProfile) {
            VeteranProtocolProfile.UNKNOWN -> null
            else -> when (chargeMode) {
                0 -> ChargeStatus.IDLE
                1 -> ChargeStatus.CHARGING
                2 -> ChargeStatus.FULLY_CHARGED
                else -> ChargeStatus.RESERVED(chargeMode)
            }
        }

    sealed class ChargeStatus {
        data object IDLE : ChargeStatus()
        data object CHARGING : ChargeStatus()
        data object FULLY_CHARGED : ChargeStatus()

        /** Raw wire value that does not map to a known mode. */
        data class RESERVED(val raw: Int) : ChargeStatus()
    }
}
