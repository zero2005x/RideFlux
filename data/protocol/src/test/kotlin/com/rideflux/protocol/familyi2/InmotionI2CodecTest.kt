/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi2

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class InmotionI2CodecTest {

    // ---- escape() escapes only 0xAA and 0xA5 (§2.7.3) -------------------

    @Test fun escape_leaves55Untouched() {
        val body = hex("55 55 55")
        assertArrayEquals(body, InmotionI2Codec.escape(body))
    }

    @Test fun escape_quotesAa() {
        assertArrayEquals(hex("A5 AA"), InmotionI2Codec.escape(hex("AA")))
    }

    @Test fun escape_quotesA5() {
        assertArrayEquals(hex("A5 A5"), InmotionI2Codec.escape(hex("A5")))
    }

    @Test fun escape_passesAllOtherBytes() {
        val body = hex("00 01 7F 80 54 56 FF")
        assertArrayEquals(body, InmotionI2Codec.escape(body))
    }

    // ---- XOR checksum (§6.4.2) ------------------------------------------

    @Test fun xor_onEmptyBodyIsZero() {
        assertEquals(0, InmotionI2Codec.xorChecksum8(ByteArray(0)))
    }

    @Test fun xor_foldsBytes() {
        // 0x14 ^ 0x01 ^ 0x04 = 0x11
        assertEquals(0x11, InmotionI2Codec.xorChecksum8(hex("14 01 04")))
    }

    @Test fun xor_handlesSelfInversion() {
        // A ^ A = 0 regardless of A
        val body = ByteArray(4) { it.toByte() }
        val doubled = body + body
        assertEquals(0, InmotionI2Codec.xorChecksum8(doubled))
    }
}
