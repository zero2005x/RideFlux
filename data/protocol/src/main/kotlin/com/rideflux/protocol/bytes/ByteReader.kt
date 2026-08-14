/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of RideFlux. It is licensed under the GNU General
 * Public License, version 3 or (at your option) any later version.
 * See the LICENSE file in the repository root for the full text.
 */
package com.rideflux.protocol.bytes

/**
 * Low-level byte-array readers used by the family-specific frame
 * decoders.
 *
 * All functions are zero-based on [off]. Every multi-byte read
 * validates that [off] and the read width fit within [buf] and throws
 * [IndexOutOfBoundsException] otherwise, so malformed wire data fails
 * as a decode error rather than corrupting the caller.
 *
 * The JVM models [Byte] as signed, so every single-byte read is
 * explicitly masked with `0xFF` when an unsigned interpretation is
 * desired. Signed reads compute two's-complement sign extension
 * explicitly instead of relying on implicit widening.
 */
internal object ByteReader {

    /** Read byte at [off] as an unsigned 8-bit value in the range 0..255. */
    fun u8(buf: ByteArray, off: Int): Int {
        checkRange(buf, off, 1)
        return buf[off].toInt() and 0xFF
    }

    /** Big-endian unsigned 16-bit read. */
    fun u16BE(buf: ByteArray, off: Int): Int {
        checkRange(buf, off, 2)
        return (u8(buf, off) shl 8) or u8(buf, off + 1)
    }

    /** Big-endian signed 16-bit read, two's complement. */
    fun s16BE(buf: ByteArray, off: Int): Int {
        val v = u16BE(buf, off)
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }

    /** Big-endian unsigned 32-bit read, returned as [Long] to avoid sign issues. */
    fun u32BE(buf: ByteArray, off: Int): Long {
        checkRange(buf, off, 4)
        return (u8(buf, off).toLong() shl 24) or
            (u8(buf, off + 1).toLong() shl 16) or
            (u8(buf, off + 2).toLong() shl 8) or
            u8(buf, off + 3).toLong()
    }

    /** Little-endian unsigned 16-bit read. */
    fun u16LE(buf: ByteArray, off: Int): Int {
        checkRange(buf, off, 2)
        return u8(buf, off) or (u8(buf, off + 1) shl 8)
    }

    /** Little-endian signed 16-bit read, two's complement. */
    fun s16LE(buf: ByteArray, off: Int): Int {
        val v = u16LE(buf, off)
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }

    /** Little-endian unsigned 32-bit read, returned as [Long]. */
    fun u32LE(buf: ByteArray, off: Int): Long {
        checkRange(buf, off, 4)
        return u8(buf, off).toLong() or
            (u8(buf, off + 1).toLong() shl 8) or
            (u8(buf, off + 2).toLong() shl 16) or
            (u8(buf, off + 3).toLong() shl 24)
    }

    /** Little-endian signed 32-bit read, two's complement. */
    fun s32LE(buf: ByteArray, off: Int): Int {
        val v = u32LE(buf, off)
        return v.toInt()
    }

    private fun checkRange(buf: ByteArray, off: Int, width: Int) {
        require(width > 0) { "ByteReader width must be positive: $width" }
        // Subtraction form: `off + width > buf.size` can overflow to a
        // negative value when `off` is near Int.MAX_VALUE, silently
        // bypassing the guard and failing later with an ambiguous
        // ArrayIndexOutOfBoundsException instead of the documented
        // explicit rejection.
        if (off < 0 || off > buf.size - width) {
            throw IndexOutOfBoundsException(
                "ByteReader: offset=$off width=$width out of bounds for size=${buf.size}",
            )
        }
    }
}
