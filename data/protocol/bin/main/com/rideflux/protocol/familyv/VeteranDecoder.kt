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

import com.rideflux.protocol.bytes.ByteReader
import java.util.zip.CRC32

/**
 * Decoder for Family V (Veteran / Sherman family) length-prefixed
 * frames as specified in `PROTOCOL_SPEC.md` §2.3, §3.3, and §6.2.
 *
 * Wire layout (from the spec):
 * ```
 *  offset 0..2 : magic "DC 5A 5C"
 *  offset 3    : magic4 0x20
 *  offset 4    : L       (unsigned payload length)
 *  offset 5..  : payload, big-endian except for the two word-swapped
 *                32-bit distance fields at offsets 8 and 12 (§8.3)
 *  [ offset 5+L..8+L : CRC-32/ISO-HDLC trailer, big-endian, present
 *                      when L > 38 or once negotiated for the session ]
 * ```
 *
 * CRC-32 is evaluated over bytes `[0..4+L]` (header + payload) using
 * the standard CRC-32/ISO-HDLC variant (§6.2), which is what
 * [java.util.zip.CRC32] implements.
 */
object VeteranDecoder {

    /** Indicates why a frame could not be decoded. */
    sealed class DecodeError {
        data object TooShort : DecodeError()
        data object BadMagic : DecodeError()
        data object LengthMismatch : DecodeError()
        data class BadCrc(val expected: Long, val actual: Long) : DecodeError()
    }

    /**
     * Result of a decode attempt. [Ok] carries the decoded frame and
     * the number of bytes consumed so callers can advance their
     * input buffer past multi-frame notifications.
     */
    sealed class DecodeResult {
        data class Ok(val frame: VeteranFrame, val consumedBytes: Int) : DecodeResult()
        data class Fail(val error: DecodeError) : DecodeResult()
    }

    private const val HEADER_SIZE = 5
    private const val CRC_SIZE = 4
    private const val CRC_REQUIRED_LENGTH_THRESHOLD = 38

    private val MAGIC = byteArrayOf(
        0xDC.toByte(), 0x5A.toByte(), 0x5C.toByte(), 0x20.toByte(),
    )

    /**
     * Decode a single Family V frame starting at index [offset] of
     * [buffer].
     *
     * CRC presence is auto-detected from the buffer length relative
     * to `L`:
     *  * exactly `5 + L` bytes available -> no CRC;
     *  * at least `9 + L` bytes available and (`L > 38` **or**
     *    [expectCrcAlways] is `true`) -> CRC validated.
     *
     * @param expectCrcAlways force CRC presence even for `L \u2264 38`, to
     *        model the "once negotiated" session rule in §2.3 / §6.2.
     */
    fun decode(
        buffer: ByteArray,
        offset: Int = 0,
        expectCrcAlways: Boolean = false,
    ): DecodeResult {
        val available = buffer.size - offset
        if (available < HEADER_SIZE) {
            return DecodeResult.Fail(DecodeError.TooShort)
        }
        for (i in MAGIC.indices) {
            if (buffer[offset + i] != MAGIC[i]) {
                return DecodeResult.Fail(DecodeError.BadMagic)
            }
        }

        val l = ByteReader.u8(buffer, offset + 4)
        val headerPlusPayload = HEADER_SIZE + l
        if (available < headerPlusPayload) {
            return DecodeResult.Fail(DecodeError.TooShort)
        }

        val crcExpected = expectCrcAlways || l > CRC_REQUIRED_LENGTH_THRESHOLD
        val consumed: Int
        val crcPresent: Boolean

        if (crcExpected) {
            if (available < headerPlusPayload + CRC_SIZE) {
                return DecodeResult.Fail(DecodeError.TooShort)
            }
            val computed = computeCrc32(buffer, offset, headerPlusPayload)
            val received = ByteReader.u32BE(buffer, offset + headerPlusPayload)
            if (computed != received) {
                return DecodeResult.Fail(DecodeError.BadCrc(computed, received))
            }
            consumed = headerPlusPayload + CRC_SIZE
            crcPresent = true
        } else {
            consumed = headerPlusPayload
            crcPresent = false
        }

        // Minimum payload length for §3.3 field map is 36 bytes (up to
        // hardware-PWM at offset 34..35). Shorter frames are rejected.
        if (l < 32) {
            return DecodeResult.Fail(DecodeError.LengthMismatch)
        }

        val frame = VeteranFrame(
            payloadLength = l,
            voltageHundredthsV = ByteReader.u16BE(buffer, offset + 4),
            speedTenthsKmh = ByteReader.s16BE(buffer, offset + 6),
            tripMeters = wordSwapU32(buffer, offset + 8),
            totalMeters = wordSwapU32(buffer, offset + 12),
            phaseCurrentHundredthsA = ByteReader.s16BE(buffer, offset + 16),
            temperatureHundredthsC = ByteReader.s16BE(buffer, offset + 18),
            autoPowerOffSeconds = ByteReader.u16BE(buffer, offset + 20),
            chargeMode = ByteReader.u16BE(buffer, offset + 22),
            speedAlertTenthsKmh = ByteReader.u16BE(buffer, offset + 24),
            speedTiltbackTenthsKmh = ByteReader.u16BE(buffer, offset + 26),
            firmwareVersionRaw = ByteReader.u16BE(buffer, offset + 28),
            pedalsMode = ByteReader.u16BE(buffer, offset + 30),
            pitchAngleHundredthsDeg = ByteReader.s16BE(buffer, offset + 32),
            hardwarePwmHundredthsPercent = ByteReader.u16BE(buffer, offset + 34),
            crc32Present = crcPresent,
        )
        return DecodeResult.Ok(frame, consumed)
    }

    /**
     * Read the 32-bit word-swapped distance field at [off] per §8.3.
     *
     * If the four wire bytes are `b0 b1 b2 b3`, the value is
     * `(b2 << 24) | (b3 << 16) | (b0 << 8) | b1`. Returned as [Long]
     * to avoid sign issues when the upper word exceeds 0x7FFF.
     */
    internal fun wordSwapU32(buffer: ByteArray, off: Int): Long {
        val b0 = ByteReader.u8(buffer, off).toLong()
        val b1 = ByteReader.u8(buffer, off + 1).toLong()
        val b2 = ByteReader.u8(buffer, off + 2).toLong()
        val b3 = ByteReader.u8(buffer, off + 3).toLong()
        return (b2 shl 24) or (b3 shl 16) or (b0 shl 8) or b1
    }

    /**
     * CRC-32/ISO-HDLC over [length] bytes of [buffer] starting at
     * [start]. Delegates to [CRC32] which implements the exact
     * variant required by §6.2 (polynomial 0xEDB88320, init /
     * xorout 0xFFFFFFFF, reflected in/out).
     */
    internal fun computeCrc32(buffer: ByteArray, start: Int, length: Int): Long {
        val crc = CRC32()
        crc.update(buffer, start, length)
        return crc.value
    }
}
