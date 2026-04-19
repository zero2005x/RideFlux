/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.bytes

import org.junit.Assert.assertEquals
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
}
