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

import com.rideflux.protocol.bytes.ByteReader

/**
 * Decoder for Family G (Begode / Gotway / ExtremeBull) 24-byte frames
 * as specified in `PROTOCOL_SPEC.md` §2.1 and §3.1.
 *
 * The decoder operates on already-framed buffers. Byte-stream
 * defragmentation and `55 AA` / `5A 5A 5A 5A` resynchronisation are
 * the responsibility of a higher layer (future `BegodeFrameSplitter`).
 *
 * Integer fields are **big-endian** (§3.1).
 */
object BegodeDecoder {

    const val FRAME_SIZE: Int = 24

    private const val HEADER_BYTE_0: Byte = 0x55
    private const val HEADER_BYTE_1: Byte = 0xAA.toByte()
    private const val FOOTER_BYTE: Byte = 0x5A

    /**
     * Decode a single 24-byte Family G frame.
     *
     * Returns `null` when the header or footer does not match, so the
     * caller can resynchronise the byte stream.
     */
    fun decode(frame: ByteArray): BegodeFrame? {
        if (frame.size != FRAME_SIZE) return null
        if (frame[0] != HEADER_BYTE_0 || frame[1] != HEADER_BYTE_1) return null
        for (i in 20..23) if (frame[i] != FOOTER_BYTE) return null

        val typeCode = ByteReader.u8(frame, 18)
        val subIndex = ByteReader.u8(frame, 19)

        return when (typeCode) {
            0x00 -> decodeLiveTelemetry(frame, subIndex)
            0x04 -> decodeSettingsAndOdometer(frame, subIndex)
            else -> BegodeFrame.Unknown(
                typeCode = typeCode,
                subIndex = subIndex,
                payload = frame.copyOfRange(2, 18),
            )
        }
    }

    private fun decodeLiveTelemetry(frame: ByteArray, subIndex: Int): BegodeFrame.LiveTelemetry =
        BegodeFrame.LiveTelemetry(
            voltageHundredthsV = ByteReader.u16BE(frame, 2),
            speedHundredthsMs = ByteReader.s16BE(frame, 4),
            tripMeters = ByteReader.u32BE(frame, 6),
            phaseCurrentHundredthsA = ByteReader.s16BE(frame, 10),
            imuTempRaw = ByteReader.s16BE(frame, 12),
            pwmTenthsPercent = ByteReader.s16BE(frame, 14),
            reservedFlags = ByteReader.u16BE(frame, 16),
            subIndex = subIndex,
        )

    private fun decodeSettingsAndOdometer(
        frame: ByteArray,
        subIndex: Int,
    ): BegodeFrame.SettingsAndOdometer = BegodeFrame.SettingsAndOdometer(
        totalDistanceMeters = ByteReader.u32BE(frame, 2),
        settingsBitfield = ByteReader.u16BE(frame, 6),
        autoPowerOffSeconds = ByteReader.u16BE(frame, 8),
        tiltbackSpeedKmh = ByteReader.u16BE(frame, 10),
        ledMode = ByteReader.u8(frame, 14),
        alertBitmap = ByteReader.u8(frame, 15),
        // Per §3.1.4 only the low two bits of the light-mode byte at
        // offset 17 are significant (off / on / strobe).
        lightMode = ByteReader.u8(frame, 17) and 0x03,
        subIndex = subIndex,
    )
}

/**
 * IMU temperature conversions for Family G (§8.1). The firmware
 * variant is not signalled in-band, so callers must select the
 * appropriate sensor model out-of-band (e.g. from the `MPU6050` /
 * `MPU6500` identification string received during the ASCII
 * handshake, §9.1).
 */
object BegodeTemperature {

    /** MPU6050 native scaling. */
    fun celsiusMpu6050(raw: Int): Double = raw / 340.0 + 36.53

    /** MPU6500 native scaling. */
    fun celsiusMpu6500(raw: Int): Double = raw / 333.87 + 21.00
}

/**
 * Family G battery-percent curves (§7). Operate on the unscaled
 * voltage in hundredths of a volt (i.e. the raw U16BE field at
 * offset 2 of the live-telemetry frame).
 */
object BegodeBatteryCurve {

    /** Legacy linear curve. */
    fun linearPercent(voltageHundredthsV: Int): Int {
        if (voltageHundredthsV <= 5290) return 0
        if (voltageHundredthsV >= 6580) return 100
        return (voltageHundredthsV - 5290) / 13
    }

    /** Refined three-segment curve. */
    fun refinedPercent(voltageHundredthsV: Int): Int = when {
        voltageHundredthsV > 6680 -> 100
        voltageHundredthsV in 5441..6680 ->
            ((voltageHundredthsV - 5320) / 13.6).toInt().coerceIn(0, 100)
        voltageHundredthsV in 5121..5440 ->
            ((voltageHundredthsV - 5120) / 36.0).toInt().coerceIn(0, 100)
        else -> 0
    }
}
