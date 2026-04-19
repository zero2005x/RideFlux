/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi1

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InmotionI1CodecTest {

    // ---- escape() ---------------------------------------------------

    @Test fun escape_isIdentityWhenNoSpecialBytes() {
        val body = hex("00 01 02 03 FF FE FD")
        assertArrayEquals(body, InmotionI1Codec.escape(body))
    }

    @Test fun escape_quotesAaLowerByte() {
        val body = hex("AA")
        assertArrayEquals(hex("A5 AA"), InmotionI1Codec.escape(body))
    }

    @Test fun escape_quotes55() {
        val body = hex("55")
        assertArrayEquals(hex("A5 55"), InmotionI1Codec.escape(body))
    }

    @Test fun escape_quotesA5() {
        val body = hex("A5")
        assertArrayEquals(hex("A5 A5"), InmotionI1Codec.escape(body))
    }

    @Test fun escape_roundTripsThroughAllEscapedValues() {
        val body = hex("AA 55 A5 00 AA A5 55 11 22")
        val stuffed = InmotionI1Codec.escape(body)
        val back = InmotionI1Codec.unescape(stuffed, 0, stuffed.size)
        assertArrayEquals(body, back)
    }

    // ---- unescape() -------------------------------------------------

    @Test fun unescape_handlesDoubleEscapeAsLiteralA5() {
        val wire = hex("A5 A5")
        assertArrayEquals(hex("A5"), InmotionI1Codec.unescape(wire, 0, wire.size))
    }

    @Test fun unescape_signalsTruncatedEscape() {
        val wire = hex("01 A5")
        assertNull(InmotionI1Codec.unescape(wire, 0, wire.size))
    }

    // ---- checksum() -------------------------------------------------

    @Test fun checksum_isAdditiveModulo256() {
        // Sum 0x01+0x02+0x03 = 0x06
        assertEquals(0x06, InmotionI1Codec.checksum8(hex("01 02 03")))
    }

    @Test fun checksum_wrapsAt256() {
        // 0xFF + 0x02 = 0x101 → 0x01
        assertEquals(0x01, InmotionI1Codec.checksum8(hex("FF 02")))
    }

    @Test fun checksum_ofEmptyBodyIsZero() {
        assertEquals(0, InmotionI1Codec.checksum8(ByteArray(0)))
    }
}
