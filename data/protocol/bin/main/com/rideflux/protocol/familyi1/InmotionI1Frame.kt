/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi1

/**
 * Unstuffed Family I1 CAN envelope (`PROTOCOL_SPEC.md` §2.6.3).
 *
 * Instances carry the full 16-byte standard envelope plus an optional
 * [exData] block (present only for extended frames where
 * [lenByte] == `0xFE`). All multi-byte integers inside [data8] and
 * [exData] are interpreted by higher-level parsers (see §3.5.2).
 *
 * @property canId    little-endian 32-bit message selector (§3.5.1).
 * @property data8    8-byte standard payload at unstuffed offset 4..11.
 * @property lenByte  value at unstuffed offset 12 (`0x08` or `0xFE`).
 * @property chan     channel byte at offset 13 (observed `0x05`).
 * @property fmt      format byte at offset 14 (`0x00` standard, `0x01` extended).
 * @property type     frame-type byte at offset 15 (`0x00` data, `0x01` remote).
 * @property exData   variable-length extended payload, or empty for
 *                    standard frames. Length equals the U32LE EX-LEN
 *                    at unstuffed offset 4..7 per §2.6.4.
 */
data class InmotionI1Frame(
    val canId: Long,
    val data8: ByteArray,
    val lenByte: Int,
    val chan: Int,
    val fmt: Int,
    val type: Int,
    val exData: ByteArray,
) {
    init {
        require(data8.size == 8) { "data8 must be 8 bytes, got ${data8.size}" }
        require(lenByte in 0..0xFF) { "lenByte out of range: $lenByte" }
    }

    val isExtended: Boolean get() = lenByte == 0xFE

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InmotionI1Frame) return false
        return canId == other.canId &&
            data8.contentEquals(other.data8) &&
            lenByte == other.lenByte &&
            chan == other.chan &&
            fmt == other.fmt &&
            type == other.type &&
            exData.contentEquals(other.exData)
    }

    override fun hashCode(): Int {
        var h = canId.hashCode()
        h = 31 * h + data8.contentHashCode()
        h = 31 * h + lenByte
        h = 31 * h + chan
        h = 31 * h + fmt
        h = 31 * h + type
        h = 31 * h + exData.contentHashCode()
        return h
    }
}

/** Result of attempting to parse one I1 wire frame. */
sealed class InmotionI1DecodeResult {
    data class Ok(val frame: InmotionI1Frame, val consumedBytes: Int) : InmotionI1DecodeResult()
    data class Fail(val error: InmotionI1DecodeError) : InmotionI1DecodeResult()
}

/** Enumeration of decode-time failures. */
sealed class InmotionI1DecodeError {
    /** Fewer bytes available than the minimum possible frame size. */
    data object TooShort : InmotionI1DecodeError()

    /** Bytes 0..1 were not the `AA AA` preamble. */
    data object BadPreamble : InmotionI1DecodeError()

    /** Trailing `55 55` was missing at the expected position. */
    data object BadTrailer : InmotionI1DecodeError()

    /** Additive checksum of unstuffed body disagreed with the transmitted CHECK byte. */
    data class BadChecksum(val expected: Int, val actual: Int) : InmotionI1DecodeError()

    /** Value at unstuffed offset 12 is neither `0x08` nor `0xFE`. */
    data class BadLen(val value: Int) : InmotionI1DecodeError()

    /** Wire escape sequence was truncated (lone trailing `A5`). */
    data object BadEscape : InmotionI1DecodeError()

    /** Extended-frame EX-LEN was absurdly large (>= 1 MiB guard). */
    data class BadExLen(val exLen: Long) : InmotionI1DecodeError()
}
