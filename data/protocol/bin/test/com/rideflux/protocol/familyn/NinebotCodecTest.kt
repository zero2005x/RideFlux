/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyn

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [NinebotCodec] against the 禮5 and 禮6 worked examples from
 * `TEST_VECTORS.md`.
 */
class NinebotCodecTest {

    // --- 禮6.1 checksum derivation ------------------------------------

    @Test fun `checksum of 禮5 pre-checksum bytes is 0xFFD4 wire D4 FF`() {
        // From TEST_VECTORS.md 禮5: 峉(03,09,01,10,0E) = 0x2B;
        // 峉 XOR 0xFFFF = 0xFFD4; CHK_LO=0xD4, CHK_HI=0xFF.
        val preChk = hex("03 09 01 10 0E")
        assertEquals(0xFFD4, NinebotCodec.checksum16(preChk))
        assertArrayEquals(hex("D4 FF"), NinebotCodec.checksum(preChk))
    }

    @Test fun `checksum of empty byte array is 0xFFFF`() {
        // 0 XOR 0xFFFF = 0xFFFF (no bytes to sum).
        assertEquals(0xFFFF, NinebotCodec.checksum16(ByteArray(0)))
    }

    @Test fun `checksum handles sums larger than 16 bits via low-word masking`() {
        // 300 bytes each 0xFF ??sum 0x12D4D; XOR 0xFFFF within low word = 0xED2C XOR 0xFFFF = 0x12D3
        // computed directly: (0x12D4D XOR 0xFFFF) AND 0xFFFF. The
        // XOR operates on 32-bit ints but AND 0xFFFF truncates.
        val input = ByteArray(300) { 0xFF.toByte() }
        val sum = 300 * 0xFF // 76500 = 0x12AAC
        val expected = (sum xor 0xFFFF) and 0xFFFF
        assertEquals(expected, NinebotCodec.checksum16(input))
    }

    // --- 禮5.1 XOR obfuscation round-trip (禮6 in TEST_VECTORS) --------

    @Test fun `XOR obfuscation matches 禮6 table byte-for-byte`() {
        val gamma = hex("01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10")
        val plain = hex("03 09 01 10 0E D4 FF") // post-prefix plaintext
        val expected = hex("03 08 03 13 0A D1 F9") // per 禮6 wire-side

        val buf = plain.copyOf()
        NinebotCodec.xorInPlace(buf, gamma)
        assertArrayEquals("plain ??obfuscated", expected, buf)

        // Symmetric: applying again reproduces the plaintext.
        NinebotCodec.xorInPlace(buf, gamma)
        assertArrayEquals("obfuscated ??plain", plain, buf)
    }

    @Test fun `XOR with all-zero keystream is an identity`() {
        val plain = hex("03 09 01 10 0E D4 FF")
        val buf = plain.copyOf()
        NinebotCodec.xorInPlace(buf, NinebotCodec.ZERO_KEYSTREAM)
        assertArrayEquals(plain, buf)
    }

    @Test fun `XOR position zero is always emitted in clear`() {
        val gamma = ByteArray(16) { 0xFF.toByte() }
        val buf = byteArrayOf(0x03, 0x09, 0x01, 0x10, 0x0E, 0xD4.toByte(), 0xFF.toByte())
        val expectedByteZero = buf[0]
        NinebotCodec.xorInPlace(buf, gamma)
        assertEquals("position 0 unchanged", expectedByteZero, buf[0])
    }

    @Test fun `XOR rolls over at position 17 (j=16 uses gamma 15, j=17 wraps to gamma 0)`() {
        // Craft a 20-byte post-prefix buffer; verify wrap-around.
        val gamma = ByteArray(16) { (it + 1).toByte() } // 01..10
        val buf = ByteArray(20) // all zeros initially
        NinebotCodec.xorInPlace(buf, gamma)
        // pos 1 ??帠[0]=01; pos 16 ??帠[15]=10; pos 17 ??帠[0]=01 again.
        assertEquals(0x01.toByte(), buf[1])
        assertEquals(0x10.toByte(), buf[16])
        assertEquals(0x01.toByte(), buf[17])
    }

    @Test fun `XOR rejects wrong-size keystream`() {
        val buf = ByteArray(5)
        assertThrows(IllegalArgumentException::class.java) {
            NinebotCodec.xorInPlace(buf, ByteArray(15))
        }
    }

    @Test fun `XOR handles empty buffer without error`() {
        NinebotCodec.xorInPlace(ByteArray(0), NinebotCodec.ZERO_KEYSTREAM)
        assertTrue("no-op on empty buffer", true)
    }
}


