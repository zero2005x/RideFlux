/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyk

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins decoder behaviour to the hex vector in
 * `clean-room/spec/TEST_VECTORS.md` §3 and the Family K live-page-B
 * field layout from `PROTOCOL_SPEC.md` §3.2.2.
 */
class KingSongDecoderTest {

    @Test fun `live page A matches TEST_VECTORS section 3`() {
        val frame = hex(
            """
            AA 55 F0 19 E8 03 10 27  00 00 2C 01 A0 0F 05 E0
            A9 00 5A 5A
            """,
        )

        val decoded = KingSongDecoder.decode(frame)

        assertTrue(decoded is KingSongFrame.LivePageA)
        decoded as KingSongFrame.LivePageA

        assertEquals(6640, decoded.voltageHundredthsV)
        assertEquals(66.40, decoded.voltageVolts, 1e-9)

        assertEquals(1000, decoded.speedHundredthsKmh)
        assertEquals(10.00, decoded.speedKmh, 1e-9)

        assertEquals(10_000L, decoded.totalDistanceMeters)

        assertEquals(300, decoded.currentHundredthsA)
        assertEquals(3.00, decoded.currentAmps, 1e-9)

        assertEquals(4000, decoded.temperatureHundredthsC)
        assertEquals(40.00, decoded.temperatureCelsius, 1e-9)

        assertTrue(decoded.modeMarkerPresent)
        assertEquals(5, decoded.modeEnum)

        assertEquals(0, decoded.subIndex)
    }

    @Test fun `live page A current is signed via high byte (spec section 8_5)`() {
        // Payload current bytes = 0x00 (low), 0xFF (high) -> S16LE = -256.
        val frame = ByteArray(KingSongDecoder.FRAME_SIZE).also {
            it[0] = 0xAA.toByte(); it[1] = 0x55
            it[10] = 0x00; it[11] = 0xFF.toByte()
            it[16] = 0xA9.toByte()
            it[18] = 0x5A; it[19] = 0x5A
        }
        val decoded = KingSongDecoder.decode(frame) as KingSongFrame.LivePageA
        assertEquals(-256, decoded.currentHundredthsA)
        assertEquals(-2.56, decoded.currentAmps, 1e-9)
    }

    @Test fun `live page A reports no mode when marker absent`() {
        val frame = ByteArray(KingSongDecoder.FRAME_SIZE).also {
            it[0] = 0xAA.toByte(); it[1] = 0x55
            it[14] = 0x42 // would-be mode enum
            it[15] = 0x00 // marker absent
            it[16] = 0xA9.toByte()
            it[18] = 0x5A; it[19] = 0x5A
        }
        val decoded = KingSongDecoder.decode(frame) as KingSongFrame.LivePageA
        assertFalse(decoded.modeMarkerPresent)
        assertEquals(0, decoded.modeEnum)
    }

    @Test fun `live page B decodes trip and top speed`() {
        val frame = ByteArray(KingSongDecoder.FRAME_SIZE).also {
            it[0] = 0xAA.toByte(); it[1] = 0x55
            // Offset 2..5 LE u32: 20000 metres -> 0x00004E20
            it[2] = 0x20; it[3] = 0x4E; it[4] = 0x00; it[5] = 0x00
            // Offset 8..9 LE u16: top speed 0x09C4 = 2500 -> 25.00 km/h
            it[8] = 0xC4.toByte(); it[9] = 0x09
            it[12] = 0x01 // fan on
            it[13] = 0x00 // not charging
            // Offset 14..15 LE u16: temp 0x0FA0 = 4000 -> 40.00 C
            it[14] = 0xA0.toByte(); it[15] = 0x0F
            it[16] = 0xB9.toByte()
            it[18] = 0x5A; it[19] = 0x5A
        }
        val decoded = KingSongDecoder.decode(frame)
        assertTrue(decoded is KingSongFrame.LivePageB)
        decoded as KingSongFrame.LivePageB

        assertEquals(20_000L, decoded.tripDistanceMeters)
        assertEquals(2500, decoded.topSpeedHundredthsKmh)
        assertEquals(25.00, decoded.topSpeedKmh, 1e-9)
        assertTrue(decoded.fanOn)
        assertFalse(decoded.charging)
        assertEquals(4000, decoded.temperatureHundredthsC)
    }

    @Test fun `decoder rejects wrong header`() {
        val frame = ByteArray(KingSongDecoder.FRAME_SIZE).also {
            it[0] = 0x55; it[1] = 0xAA.toByte()
            it[18] = 0x5A; it[19] = 0x5A
        }
        assertNull(KingSongDecoder.decode(frame))
    }

    @Test fun `decoder rejects wrong tail`() {
        val frame = ByteArray(KingSongDecoder.FRAME_SIZE).also {
            it[0] = 0xAA.toByte(); it[1] = 0x55
            it[18] = 0x00; it[19] = 0x5A
        }
        assertNull(KingSongDecoder.decode(frame))
    }

    @Test fun `decoder falls through to Unknown for unmapped commands`() {
        val frame = ByteArray(KingSongDecoder.FRAME_SIZE).also {
            it[0] = 0xAA.toByte(); it[1] = 0x55
            it[16] = 0xBB.toByte() // device name (not yet decoded)
            it[17] = 0x02
            it[18] = 0x5A; it[19] = 0x5A
        }
        val decoded = KingSongDecoder.decode(frame)
        assertTrue(decoded is KingSongFrame.Unknown)
        decoded as KingSongFrame.Unknown
        assertEquals(0xBB, decoded.commandCode)
        assertEquals(2, decoded.subIndex)
        assertEquals(14, decoded.payload.size)
    }
}
