/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi2

/**
 * Unstuffed Family I2 application frame (`PROTOCOL_SPEC.md` §2.7.1).
 *
 * Wire layout after the `AA AA` preamble is, once unescaped:
 * `FLAGS | LEN | CMD | DATA[LEN-1] | CHECK`. [cmd] stored here has
 * already been masked against `0x7F` per §2.7.1 on the receive path;
 * the original high bit is preserved in [cmdRawHighBitSet] for
 * diagnostic callers.
 *
 * Note on the §2.7.1 length comment: the current spec text reads
 * "LEN + 4 escape-decoded bytes after the preamble" but the
 * enumerated body (`FLAGS + LEN + CMD + DATA + CHECK`) contains
 * exactly `LEN + 3` bytes (1 + 1 + 1 + (LEN-1) + 1). This
 * implementation follows the enumeration.
 */
data class InmotionI2Frame(
    val flags: Int,
    val cmd: Int,
    val data: ByteArray,
    val cmdRawHighBitSet: Boolean = false,
) {
    init {
        require(flags in 0..0xFF) { "flags out of range: $flags" }
        require(cmd in 0..0x7F) { "cmd must be 7-bit after masking, got $cmd" }
        require(data.size <= 0xFE) { "data too large for U8 LEN-1: ${data.size}" }
    }

    /** `LEN = data.size + 1` (the CMD byte is counted, §2.7.1). */
    val lenByte: Int get() = data.size + 1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InmotionI2Frame) return false
        return flags == other.flags &&
            cmd == other.cmd &&
            data.contentEquals(other.data) &&
            cmdRawHighBitSet == other.cmdRawHighBitSet
    }

    override fun hashCode(): Int {
        var h = flags
        h = 31 * h + cmd
        h = 31 * h + data.contentHashCode()
        h = 31 * h + cmdRawHighBitSet.hashCode()
        return h
    }
}

/** Result of attempting to parse one I2 wire frame. */
sealed class InmotionI2DecodeResult {
    data class Ok(val frame: InmotionI2Frame, val consumedBytes: Int) : InmotionI2DecodeResult()
    data class Fail(val error: InmotionI2DecodeError) : InmotionI2DecodeResult()
}

/** Enumeration of decode-time failures. */
sealed class InmotionI2DecodeError {
    data object TooShort : InmotionI2DecodeError()
    data object BadPreamble : InmotionI2DecodeError()
    data object BadEscape : InmotionI2DecodeError()
    data class BadFlags(val value: Int) : InmotionI2DecodeError()
    data class BadLen(val value: Int) : InmotionI2DecodeError()
    data class BadChecksum(val expected: Int, val actual: Int) : InmotionI2DecodeError()
}
