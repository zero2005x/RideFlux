/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.bytes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ByteReaderTest {

    private val sample = byteArrayOf(
        0x19.toByte(), 0xF0.toByte(), // BE: 0x19F0 = 6640 ; LE: 0xF019 = 61465
        0xFD.toByte(), 0xCA.toByte(), // BE signed: -566
        0x00.toByte(), 0x0A.toByte(), 0x4A.toByte(), 0x12.toByte(), // BE U32: 674322
        0x10.toByte(), 0x27.toByte(), 0x00.toByte(), 0x00.toByte(), // LE U32: 10000
    )

    @Test fun `u8 masks sign bit`() {
        assertEquals(0xFD, ByteReader.u8(sample, 2))
    }

    @Test fun `u16BE reads unsigned`() {
        assertEquals(6640, ByteReader.u16BE(sample, 0))
    }

    @Test fun `s16BE sign-extends`() {
        assertEquals(-566, ByteReader.s16BE(sample, 2))
    }

    @Test fun `u32BE reads unsigned`() {
        assertEquals(674322L, ByteReader.u32BE(sample, 4))
    }

    @Test fun `u16LE reads unsigned`() {
        assertEquals(0xF019, ByteReader.u16LE(sample, 0))
    }

    @Test fun `s16LE sign-extends`() {
        // 0xF019 as signed 16-bit => -4071
        assertEquals(-4071, ByteReader.s16LE(sample, 0))
    }

    @Test fun `u32LE reads unsigned`() {
        assertEquals(10_000L, ByteReader.u32LE(sample, 8))
    }

    // u32LE truncation in s32LE: `Long.toInt()` keeps the low 32 bits,
    // which is exactly the two's-complement interpretation of the wire
    // value. These edge cases are correctness-critical for malformed
    // wire data; pin them so a future u32LE change cannot silently
    // alter the mapping.
    @Test fun `s32LE truncates 0xFFFFFFFF to -1`() {
        val buf = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, ByteReader.s32LE(buf, 0))
    }

    @Test fun `s32LE truncates 0x80000000 to Int MIN_VALUE`() {
        val buf = byteArrayOf(0x00, 0x00, 0x00, 0x80.toByte())
        assertEquals(Int.MIN_VALUE, ByteReader.s32LE(buf, 0))
    }

    // The bounds check uses subtraction form because `off + width`
    // can overflow to a negative value near Int.MAX_VALUE, silently
    // bypassing the guard with an addition-form check.
    @Test fun `checkRange rejects offset that would overflow the addition form`() {
        assertThrows(IndexOutOfBoundsException::class.java) {
            ByteReader.u16LE(sample, Int.MAX_VALUE - 1)
        }
    }

    @Test fun `checkRange rejects negative offset`() {
        assertThrows(IndexOutOfBoundsException::class.java) {
            ByteReader.u8(sample, -1)
        }
    }
}
