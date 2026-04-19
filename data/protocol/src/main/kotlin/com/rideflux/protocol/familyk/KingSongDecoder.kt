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

import com.rideflux.protocol.bytes.ByteReader

/**
 * Decoder for Family K (KingSong) 20-byte fixed-length frames as
 * specified in `PROTOCOL_SPEC.md` §2.2 and §3.2.
 *
 * Family K frames arrive one-per-notification in practice but this
 * decoder operates on pre-framed buffers. Integer fields inside the
 * payload are **little-endian** (§2.2).
 */
object KingSongDecoder {

    const val FRAME_SIZE: Int = 20

    private const val HEADER_HIGH: Byte = 0xAA.toByte()
    private const val HEADER_LOW: Byte = 0x55
    private const val TAIL_BYTE: Byte = 0x5A

    const val CMD_LIVE_PAGE_A: Int = 0xA9
    const val CMD_LIVE_PAGE_B: Int = 0xB9

    private const val MODE_MARKER: Int = 0xE0

    /**
     * Decode a single 20-byte Family K frame.
     *
     * Returns `null` when header or tail bytes do not match so that
     * callers can reject malformed buffers.
     */
    fun decode(frame: ByteArray): KingSongFrame? {
        if (frame.size != FRAME_SIZE) return null
        if (frame[0] != HEADER_HIGH || frame[1] != HEADER_LOW) return null
        if (frame[18] != TAIL_BYTE || frame[19] != TAIL_BYTE) return null

        val commandCode = ByteReader.u8(frame, 16)
        val subIndex = ByteReader.u8(frame, 17)

        return when (commandCode) {
            CMD_LIVE_PAGE_A -> decodeLivePageA(frame, subIndex)
            CMD_LIVE_PAGE_B -> decodeLivePageB(frame, subIndex)
            else -> KingSongFrame.Unknown(
                commandCode = commandCode,
                subIndex = subIndex,
                payload = frame.copyOfRange(2, 16),
            )
        }
    }

    private fun decodeLivePageA(frame: ByteArray, subIndex: Int): KingSongFrame.LivePageA {
        val markerPresent = ByteReader.u8(frame, 15) == MODE_MARKER
        return KingSongFrame.LivePageA(
            voltageHundredthsV = ByteReader.u16LE(frame, 2),
            speedHundredthsKmh = ByteReader.u16LE(frame, 4),
            totalDistanceMeters = ByteReader.u32LE(frame, 6),
            // §8.5: low byte unsigned at +0, signed high byte at +1.
            currentHundredthsA = ByteReader.s16LE(frame, 10),
            temperatureHundredthsC = ByteReader.u16LE(frame, 12),
            modeMarkerPresent = markerPresent,
            modeEnum = if (markerPresent) ByteReader.u8(frame, 14) else 0,
            subIndex = subIndex,
        )
    }

    private fun decodeLivePageB(frame: ByteArray, subIndex: Int): KingSongFrame.LivePageB =
        KingSongFrame.LivePageB(
            tripDistanceMeters = ByteReader.u32LE(frame, 2),
            topSpeedHundredthsKmh = ByteReader.u16LE(frame, 8),
            fanOn = ByteReader.u8(frame, 12) != 0,
            charging = ByteReader.u8(frame, 13) != 0,
            temperatureHundredthsC = ByteReader.u16LE(frame, 14),
            subIndex = subIndex,
        )
}
