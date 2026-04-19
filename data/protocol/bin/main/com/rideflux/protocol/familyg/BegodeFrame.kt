/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of RideFlux. It is licensed under the GNU General
 * Public License, version 3 or (at your option) any later version.
 * See the LICENSE file in the repository root for the full text.
 */
package com.rideflux.protocol.familyg

/**
 * Immutable telemetry model for Family G (Begode / Gotway / ExtremeBull)
 * as specified in `PROTOCOL_SPEC.md` §3.1.
 *
 * Frames are 24 bytes; the one-byte type code at offset 18 selects the
 * payload layout.
 */
sealed class BegodeFrame {

    /** Sub-packet index at offset 19 of the raw frame. */
    abstract val subIndex: Int

    /**
     * Type `0x00` live telemetry (§3.1.1).
     *
     * Fields are stored at their native resolution (hundredths of the
     * SI unit for voltage/speed/current, metres for distance, raw
     * sensor counts for IMU temperature) to avoid lossy conversions
     * at decode time. Convenience properties expose SI units.
     *
     * PWM: test vector §1 locks in `raw=1 ⇒ 0.10 %`, so the field is
     * stored as tenths of a percent.
     */
    data class LiveTelemetry(
        val voltageHundredthsV: Int,
        val speedHundredthsMs: Int,
        val tripMeters: Long,
        val phaseCurrentHundredthsA: Int,
        val imuTempRaw: Int,
        val pwmTenthsPercent: Int,
        val reservedFlags: Int,
        override val subIndex: Int,
    ) : BegodeFrame() {

        val voltageVolts: Double get() = voltageHundredthsV / 100.0

        /** Speed in km/h derived from the raw 0.01 m/s field (§3.1.1). */
        val speedKmh: Double get() = speedHundredthsMs * 3.6 / 100.0

        val phaseCurrentAmps: Double get() = phaseCurrentHundredthsA / 100.0

        /** PWM duty cycle in percent (0.0 .. ~100.0). */
        val pwmPercent: Double get() = pwmTenthsPercent / 10.0
    }

    /**
     * Type `0x04` odometer and device settings (§3.1.4).
     *
     * Raw `settingsBitfield` (U16BE) is kept as-is; sub-fields are
     * exposed through derived properties. `lightMode` is masked with
     * `0x03` per test vector §2.
     */
    data class SettingsAndOdometer(
        val totalDistanceMeters: Long,
        val settingsBitfield: Int,
        val autoPowerOffSeconds: Int,
        val tiltbackSpeedKmh: Int,
        val ledMode: Int,
        val alertBitmap: Int,
        val lightMode: Int,
        override val subIndex: Int,
    ) : BegodeFrame() {

        /** Pedals mode: inverted three-bit field (§3.1.4). */
        val pedalsMode: Int get() = 2 - ((settingsBitfield ushr 13) and 0x07)
        val speedAlarmMode: Int get() = (settingsBitfield ushr 10) and 0x07
        val rollAngleMode: Int get() = (settingsBitfield ushr 7) and 0x07
        val milesMode: Boolean get() = (settingsBitfield and 0x01) != 0

        // Alert bitmap decomposition per §4.1.
        val alertWheelAlarm: Boolean get() = (alertBitmap and 0x01) != 0
        val alertSpeedLevel2: Boolean get() = (alertBitmap and 0x02) != 0
        val alertSpeedLevel1: Boolean get() = (alertBitmap and 0x04) != 0
        val alertLowVoltage: Boolean get() = (alertBitmap and 0x08) != 0
        val alertOverVoltage: Boolean get() = (alertBitmap and 0x10) != 0
        val alertOverTemperature: Boolean get() = (alertBitmap and 0x20) != 0
        val alertHallSensor: Boolean get() = (alertBitmap and 0x40) != 0
        val alertTransportMode: Boolean get() = (alertBitmap and 0x80) != 0
    }

    /**
     * A frame with a type code not yet implemented. Raw payload is
     * preserved so higher layers can log and move on (forward
     * compatibility per §12).
     */
    data class Unknown(
        val typeCode: Int,
        override val subIndex: Int,
        val payload: ByteArray,
    ) : BegodeFrame() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Unknown) return false
            return typeCode == other.typeCode &&
                subIndex == other.subIndex &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = typeCode
            result = 31 * result + subIndex
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }
}
