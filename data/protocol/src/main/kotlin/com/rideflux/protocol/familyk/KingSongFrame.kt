/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of RideFlux. It is licensed under the GNU General
 * Public License, version 3 or (at your option) any later version.
 * See the LICENSE file in the repository root for the full text.
 */
package com.rideflux.protocol.familyk

/**
 * Immutable telemetry model for Family K (KingSong) as specified in
 * `PROTOCOL_SPEC.md` §3.2.
 *
 * Family K frames are fixed-length (20 bytes). The command byte at
 * offset 16 selects the payload layout; the sub-index at offset 17
 * is preserved on every variant.
 */
sealed class KingSongFrame {

    abstract val subIndex: Int

    /**
     * Live page A (`0xA9`, §3.2.1).
     *
     * Mode enum is only meaningful when [modeMarkerPresent] is true
     * (the marker byte `0xE0` at offset 15).
     *
     * Current is decoded as S16LE per §8.5.
     */
    data class LivePageA(
        val voltageHundredthsV: Int,
        val speedHundredthsKmh: Int,
        val totalDistanceMeters: Long,
        val currentHundredthsA: Int,
        val temperatureHundredthsC: Int,
        val modeMarkerPresent: Boolean,
        val modeEnum: Int,
        override val subIndex: Int,
    ) : KingSongFrame() {

        val voltageVolts: Double get() = voltageHundredthsV / 100.0
        val speedKmh: Double get() = speedHundredthsKmh / 100.0
        val currentAmps: Double get() = currentHundredthsA / 100.0
        val temperatureCelsius: Double get() = temperatureHundredthsC / 100.0
    }

    /**
     * Live page B (`0xB9`, §3.2.2).
     */
    data class LivePageB(
        val tripDistanceMeters: Long,
        val topSpeedHundredthsKmh: Int,
        val fanOn: Boolean,
        val charging: Boolean,
        val temperatureHundredthsC: Int,
        override val subIndex: Int,
    ) : KingSongFrame() {

        val topSpeedKmh: Double get() = topSpeedHundredthsKmh / 100.0
        val temperatureCelsius: Double get() = temperatureHundredthsC / 100.0
    }

    /**
     * A frame whose command code is not yet mapped. Payload bytes
     * 2..15 are preserved verbatim.
     */
    data class Unknown(
        val commandCode: Int,
        override val subIndex: Int,
        val payload: ByteArray,
    ) : KingSongFrame() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Unknown) return false
            return commandCode == other.commandCode &&
                subIndex == other.subIndex &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = commandCode
            result = 31 * result + subIndex
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }
}
