/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi1

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4.3.1 / §4.3.2 tests for I1 state-word and alert-record decoders.
 *
 * Vectors are pinned to `clean-room/spec/TEST_VECTORS.md` entries
 * I1.7 (alert decode) and I1.8 (state word).
 */
class InmotionI1StatusTest {

    // ---- §4.3.1 state word — TEST_VECTORS I1.8 --------------------------

    @Test
    fun `legacy V10S drive state decodes to Drive`() {
        val decoded = InmotionI1StateWord.decodeLegacy(0x00000001L)
        assertEquals(1, decoded.code)
        assertEquals("Drive", decoded.mode)
        assertEquals("Drive", decoded.displayString)
    }

    @Test
    fun `modern V10F drive + engine off decodes to Drive - Engine off`() {
        val decoded = InmotionI1StateWord.decodeModern(0x00000021L)
        assertEquals(2, decoded.highNibble)
        assertEquals("Drive", decoded.primary)
        assertTrue(decoded.engineOff)
        assertEquals("Drive - Engine off", decoded.displayString)
    }

    @Test
    fun `modern drive without low nibble == 1 omits suffix`() {
        val decoded = InmotionI1StateWord.decodeModern(0x00000020L)
        assertFalse(decoded.engineOff)
        assertEquals("Drive", decoded.displayString)
    }

    @Test
    fun `legacy unmapped code falls through to Unknown`() {
        val decoded = InmotionI1StateWord.decodeLegacy(0x0000000FL)
        assertEquals(15, decoded.code)
        assertEquals("Unknown", decoded.mode)
    }

    @Test
    fun `modern unmapped high nibble reports code in string`() {
        val decoded = InmotionI1StateWord.decodeModern(0x00000090L)
        assertEquals(9, decoded.highNibble)
        assertEquals("Unknown code 9", decoded.primary)
    }

    @Test
    fun `state word upper bits are ignored by legacy decoder`() {
        // Only the low nibble matters.
        val decoded = InmotionI1StateWord.decodeLegacy(0xDEADBEEFL and 0xFFFFFFFFL)
        assertEquals(0xF, decoded.code)
        assertEquals("Unknown", decoded.mode)
    }

    // ---- §4.3.2 alert record — TEST_VECTORS I1.7 ------------------------

    @Test
    fun `I1_7 tilt-back alert decodes per TEST_VECTORS`() {
        val data8 = hex("06 00 07 D0 00 00 2E E0")
        val alert = InmotionI1AlertRecord.parse(data8)
        assertEquals(0x06, alert.alertId)
        assertEquals(2000, alert.aValue1)
        assertEquals(12000, alert.aValue2)
        // |12000/3812| * 3.6 ≈ 11.332...
        assertEquals(11.33, alert.aSpeedKmh, 0.01)
        val event = alert.event
        assertTrue(event is InmotionI1AlertRecord.Event.TiltBack)
        event as InmotionI1AlertRecord.Event.TiltBack
        assertEquals(2.0, event.limit, 1e-9)
        assertEquals(11.33, event.speedKmh, 0.01)
    }

    @Test
    fun `alert aValue2 is signed and yields absolute speed`() {
        // aValue2 = -12000 should still give the same magnitude km/h.
        val data8 = hex("06 00 07 D0 FF FF D1 20")
        val alert = InmotionI1AlertRecord.parse(data8)
        assertEquals(-12000, alert.aValue2)
        assertEquals(11.33, alert.aSpeedKmh, 0.01)
    }

    @Test
    fun `alert low battery uses aValue2 over 100 as volts`() {
        // alertId 0x20, aValue2 = 6640 ⇒ 66.40 V.
        val data8 = hex("20 00 00 00 00 00 19 F0")
        val alert = InmotionI1AlertRecord.parse(data8)
        val event = alert.event
        assertTrue(event is InmotionI1AlertRecord.Event.LowBattery)
        event as InmotionI1AlertRecord.Event.LowBattery
        assertEquals(66.40, event.voltageV, 1e-9)
    }

    @Test
    fun `alert unknown id preserves raw data8`() {
        val raw = hex("FA 00 00 00 00 00 00 00")
        val alert = InmotionI1AlertRecord.parse(raw)
        val event = alert.event
        assertTrue(event is InmotionI1AlertRecord.Event.Unknown)
        event as InmotionI1AlertRecord.Event.Unknown
        assertEquals(0xFA, event.alertId)
        assertTrue(raw.contentEquals(event.rawData8))
    }

    @Test
    fun `alert record CAN-ID constant matches spec 0x0F780101`() {
        assertEquals(0x0F780101L, InmotionI1CommandBuilder.CAN_ID_ALERT)
    }
}
