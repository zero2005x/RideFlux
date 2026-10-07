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
 * Decoder for Family V (Veteran / Sherman family) length-prefixed frames.
 *
 * Wire layout. Field offsets are measured from frame byte 0 (`0xDC`):
 * ```
 *  offset 0..2 : magic "DC 5A 5C"
 *  offset 3    : length  (unsigned, counts the bytes after the 4-byte header;
 *                         0x20 on every real frame seen so far, i.e. 36 bytes in all)
 *  offset 4..  : fields, big-endian except for the two word-swapped 32-bit
 *                distance fields at offsets 8 and 12; the voltage starts at offset 4
 *  [ last 4 bytes : CRC-32/ISO-HDLC over the first `length` bytes, big-endian, present
 *                   when length > 38 or once a CRC frame has been seen in the session ]
 * ```
 * A frame is therefore always `length + 4` bytes long, with or without the CRC.
 *
 * Earlier versions read the length from offset 4, which is the voltage's high byte. Real
 * frames only lined up with that when the voltage happened to give a value near the frame
 * size, so they were never decoded on their own, every other one was lost in a stream,
 * and above 99.83 V or below 79.36 V nothing was accepted at all. The CRC geometry above
 * comes from a community implementation and has no real capture behind it.
 *
 * CRC-32 uses the standard CRC-32/ISO-HDLC variant, which is what
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

    /** Magic (3 bytes) plus the length byte. */
    private const val HEADER_SIZE = 4
    private const val CRC_REQUIRED_LENGTH_THRESHOLD = 38

    /** The last field (hardware PWM) ends at offset 35, so a frame carries at least 36 bytes. */
    private const val MIN_LENGTH = 32

    private val MAGIC = byteArrayOf(0xDC.toByte(), 0x5A.toByte(), 0x5C.toByte())

    /**
     * Decode a single Family V frame starting at index [offset] of
     * [buffer].
     *
     * A frame is `length + 4` bytes; whether its last four bytes are a CRC is decided by the
     * length (`> 38`) or by [expectCrcAlways].
     *
     * **CRC caveat:** the length-based detection can desynchronise a stream when the sender's
     * actual CRC policy differs from the detected one. The caller should pass the negotiated
     * CRC state in [expectCrcAlways] once it knows it.
     *
     * @param expectCrcAlways force CRC presence even for `length\u2264 38`, to model a wheel that
     *        has already sent a CRC frame in this session.
     */
    fun decode(
        buffer: ByteArray,
        offset: Int = 0,
        expectCrcAlways: Boolean = false,
        profile: VeteranProtocolProfile = VeteranProtocolProfile.LEGACY,
    ): DecodeResult {
        // Reject an invalid offset explicitly so a negative/out-of-range
        // offset fails as a decode error instead of crashing the caller
        // with an ArrayIndexOutOfBoundsException (ByteReader performs no
        // bounds checks).
        if (offset < 0 || offset > buffer.size) {
            return DecodeResult.Fail(DecodeError.TooShort)
        }
        val available = buffer.size - offset
        if (available < HEADER_SIZE) {
            return DecodeResult.Fail(DecodeError.TooShort)
        }
        for (i in MAGIC.indices) {
            if (buffer[offset + i] != MAGIC[i]) {
                return DecodeResult.Fail(DecodeError.BadMagic)
            }
        }

        val length = ByteReader.u8(buffer, offset + 3)
        // Rejected before waiting for the rest: a frame this short cannot hold the fields, so
        // it is a false magic rather than a frame still arriving.
        if (length < MIN_LENGTH) {
            return DecodeResult.Fail(DecodeError.LengthMismatch)
        }
        val total = HEADER_SIZE + length
        if (available < total) {
            return DecodeResult.Fail(DecodeError.TooShort)
        }

        val crcPresent = expectCrcAlways || length > CRC_REQUIRED_LENGTH_THRESHOLD
        if (crcPresent) {
            val computed = computeCrc32(buffer, offset, length)
            val received = ByteReader.u32BE(buffer, offset + length)
            if (computed != received) {
                return DecodeResult.Fail(DecodeError.BadCrc(computed, received))
            }
        }
        val consumed = total

        val b28 = ByteReader.u8(buffer, offset + 28)
        val b29 = ByteReader.u8(buffer, offset + 29)
        val b30 = ByteReader.u8(buffer, offset + 30)

        val frame = VeteranFrame(
            declaredLength = length,
            voltageHundredthsV = ByteReader.u16BE(buffer, offset + 4),
            speedTenthsKmh = ByteReader.s16BE(buffer, offset + 6),
            tripMeters = wordSwapU32(buffer, offset + 8),
            totalMeters = wordSwapU32(buffer, offset + 12),
            phaseCurrentHundredthsA = ByteReader.s16BE(buffer, offset + 16),
            temperatureHundredthsC = ByteReader.s16BE(buffer, offset + 18),
            autoPowerOffSeconds = ByteReader.u16BE(buffer, offset + 20),
            chargeMode = when (profile) {
                VeteranProtocolProfile.MODERN_NOSFET -> ByteReader.u8(buffer, offset + 23)
                VeteranProtocolProfile.LEGACY -> ByteReader.u16BE(buffer, offset + 22)
                VeteranProtocolProfile.UNKNOWN -> 0
            },
            speedAlertTenthsKmh = ByteReader.u16BE(buffer, offset + 24),
            speedTiltbackTenthsKmh = ByteReader.u16BE(buffer, offset + 26),
            firmwareVersionRaw = when (profile) {
                VeteranProtocolProfile.MODERN_NOSFET -> (b30 shl 16) or (b28 shl 8) or b29
                VeteranProtocolProfile.LEGACY -> ByteReader.u16BE(buffer, offset + 28)
                VeteranProtocolProfile.UNKNOWN -> 0
            },
            pedalsMode = when (profile) {
                VeteranProtocolProfile.MODERN_NOSFET -> ByteReader.u8(buffer, offset + 31)
                VeteranProtocolProfile.LEGACY -> ByteReader.u16BE(buffer, offset + 30)
                VeteranProtocolProfile.UNKNOWN -> 0
            },
            pitchAngleHundredthsDeg = ByteReader.s16BE(buffer, offset + 32),
            hardwarePwmHundredthsPercent = ByteReader.u16BE(buffer, offset + 34),
            crc32Present = crcPresent,
            protocolProfile = profile,
            rawByte28 = b28,
            rawByte29 = b29,
            rawByte30 = b30,
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
