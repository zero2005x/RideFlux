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
 * All functions are zero-based on [off] and do not perform bounds
 * checks beyond what the JVM already enforces on [ByteArray] access;
 * callers are expected to validate frame length before decoding.
 *
 * The JVM models [Byte] as signed, so every single-byte read is
 * explicitly masked with `0xFF` when an unsigned interpretation is
 * desired. Two-byte signed reads are obtained by promoting the
 * unsigned value through [Short] so that the high bit is
 * sign-extended by the JVM.
 */
internal object ByteReader {

    /** Read byte at [off] as an unsigned 8-bit value in the range 0..255. */
    fun u8(buf: ByteArray, off: Int): Int = buf[off].toInt() and 0xFF

    /** Big-endian unsigned 16-bit read. */
    fun u16BE(buf: ByteArray, off: Int): Int =
        (u8(buf, off) shl 8) or u8(buf, off + 1)

    /** Big-endian signed 16-bit read, two's complement. */
    fun s16BE(buf: ByteArray, off: Int): Int =
        u16BE(buf, off).toShort().toInt()

    /** Big-endian unsigned 32-bit read, returned as [Long] to avoid sign issues. */
    fun u32BE(buf: ByteArray, off: Int): Long =
        (u8(buf, off).toLong() shl 24) or
            (u8(buf, off + 1).toLong() shl 16) or
            (u8(buf, off + 2).toLong() shl 8) or
            u8(buf, off + 3).toLong()

    /** Little-endian unsigned 16-bit read. */
    fun u16LE(buf: ByteArray, off: Int): Int =
        u8(buf, off) or (u8(buf, off + 1) shl 8)

    /** Little-endian signed 16-bit read, two's complement. */
    fun s16LE(buf: ByteArray, off: Int): Int =
        u16LE(buf, off).toShort().toInt()

    /** Little-endian unsigned 32-bit read, returned as [Long]. */
    fun u32LE(buf: ByteArray, off: Int): Long =
        u8(buf, off).toLong() or
            (u8(buf, off + 1).toLong() shl 8) or
            (u8(buf, off + 2).toLong() shl 16) or
            (u8(buf, off + 3).toLong() shl 24)
}
