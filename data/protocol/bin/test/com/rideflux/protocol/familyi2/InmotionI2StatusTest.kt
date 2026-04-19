/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi2

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4.3.3 / §4.3.4 tests for I2 state-byte and error-bitmap decoders.
 *
 * Vectors are pinned to `clean-room/spec/TEST_VECTORS.md` entries
 * I2.6 (state bytes) and I2.7 (error bitmap).
 */
class InmotionI2StatusTest {

    // ---- §4.3.3 state bytes — TEST_VECTORS I2.6 -------------------------

    @Test
    fun `I2_6 state A 0x41 decodes to Drive + motor active`() {
        val a = InmotionI2StateA(0x41)
        assertEquals(1, a.pcModeCode)
        assertEquals("Drive", a.pcMode)
        assertTrue(a.motorActive)
        assertFalse(a.charging)
        assertEquals(0, a.mcModeCode)
    }

    @Test
    fun `I2_6 state B 0x05 decodes to headlight + lifted`() {
        val b = InmotionI2StateB(0x05)
        assertTrue(b.headlightOn)
        assertFalse(b.decorativeLightOn)
        assertTrue(b.lifted)
        assertEquals(0, b.tailLightMode)
        assertFalse(b.coolingFanOrDfu)
    }

    @Test
    fun `I2_6 convenience string reads Active Lifted`() {
        val s = inmotionI2StatusString(InmotionI2StateA(0x41), InmotionI2StateB(0x05))
        assertEquals("Active Lifted", s)
    }

    @Test
    fun `status string orders Active Charging Lifted`() {
        // motorActive=1, charging=1 (state A = 0xC0), lifted=1 (state B = 0x04)
        val s = inmotionI2StatusString(InmotionI2StateA(0xC0), InmotionI2StateB(0x04))
        assertEquals("Active Charging Lifted", s)
    }

    @Test
    fun `status string is empty when no relevant bits set`() {
        assertEquals("", inmotionI2StatusString(InmotionI2StateA(0x00), InmotionI2StateB(0x00)))
    }

    @Test
    fun `state A charging bit is independent of motor active`() {
        val a = InmotionI2StateA(0x82) // pcMode=2 Shutdown, charging=1, motor=0
        assertEquals("Shutdown", a.pcMode)
        assertFalse(a.motorActive)
        assertTrue(a.charging)
    }

    // ---- §4.3.4 error bitmap — TEST_VECTORS I2.7 ------------------------

    @Test
    fun `I2_7 error bitmap 00 00 00 44 00 00 00 decodes to 2 faults`() {
        val bmp = InmotionI2ErrorBitmap.parse(hex("00 00 00 44 00 00 00"))
        // E3 bits 2..3 = 01 → INFORMATIONAL.
        assertEquals(InmotionI2Severity.INFORMATIONAL, bmp.overBusCurrentSeverity)
        assertEquals(1, bmp.overBusCurrentSeverity.code)
        // E3 bit 6 = 1 → mos_temp.
        assertTrue(bmp.mosTemp)
        // Everything else clear.
        assertEquals(InmotionI2Severity.NONE, bmp.lowBatterySeverity)
        assertFalse(bmp.underVoltage)
        assertFalse(bmp.overVoltage)
        assertFalse(bmp.motorTemp)
        assertFalse(bmp.phaseCurrentSensor)
        assertFalse(bmp.batteryTemp)
        assertFalse(bmp.motorNoLoad)
        assertFalse(bmp.hwCompatibility)
        assertTrue(bmp.hasAnyFault)
    }

    @Test
    fun `error bitmap all-zero reports no fault`() {
        val bmp = InmotionI2ErrorBitmap.parse(hex("00 00 00 00 00 00 00"))
        assertFalse(bmp.hasAnyFault)
        assertEquals(InmotionI2Severity.NONE, bmp.overBusCurrentSeverity)
        assertEquals(InmotionI2Severity.NONE, bmp.lowBatterySeverity)
    }

    @Test
    fun `E3 severity fields pack independently`() {
        // E3 bits 2..3 = 11 (CRITICAL), 4..5 = 10 (WARNING).
        // Byte = 0b0010_1100 = 0x2C.
        val bmp = InmotionI2ErrorBitmap.parse(hex("00 00 00 2C 00 00 00"))
        assertEquals(InmotionI2Severity.CRITICAL, bmp.overBusCurrentSeverity)
        assertEquals(InmotionI2Severity.WARNING, bmp.lowBatterySeverity)
        assertFalse(bmp.underVoltage)
        assertFalse(bmp.mosTemp)
    }

    @Test
    fun `E0 through E6 flags map to spec-named booleans`() {
        // One bit per byte to verify per-byte dispatch.
        // E0 bit0 → phase_current_sensor; E1 bit1 → mos_temp_sensor;
        // E2 bit2 → cannot_power_off;     E3 bit0 → under_voltage;
        // E4 bit5 → motor_block;          E5 bit4 → force_dfu;
        // E6 bit2 → fan_low_speed.
        val bmp = InmotionI2ErrorBitmap.parse(hex("01 02 04 01 20 10 04"))
        assertTrue(bmp.phaseCurrentSensor)
        assertTrue(bmp.mosTempSensor)
        assertTrue(bmp.cannotPowerOff)
        assertTrue(bmp.underVoltage)
        assertTrue(bmp.motorBlock)
        assertTrue(bmp.forceDfu)
        assertTrue(bmp.fanLowSpeed)
        // Spot-check non-set flags in the same bytes.
        assertFalse(bmp.bleCom1)         // E0 bit7
        assertFalse(bmp.externalRom)     // E1 bit7
        assertFalse(bmp.reservedE2_3)    // E2 bit3
        assertFalse(bmp.overVoltage)     // E3 bit1
        assertFalse(bmp.posture)         // E4 bit6
        assertFalse(bmp.imuOverTemp)     // E5 bit7
        assertFalse(bmp.hwCompatibility) // E6 bit1
    }

    @Test
    fun `error bitmap parse with offset skips leading bytes`() {
        // Prefix the 7 error bytes with 3 junk bytes; parse starting at offset 3.
        val bmp = InmotionI2ErrorBitmap.parse(hex("DE AD BE 00 00 00 44 00 00 00"), offset = 3)
        assertTrue(bmp.mosTemp)
        assertEquals(InmotionI2Severity.INFORMATIONAL, bmp.overBusCurrentSeverity)
    }

    @Test
    fun `error bitmap rejects short buffer`() {
        try {
            InmotionI2ErrorBitmap.parse(hex("00 00 00 00 00 00"))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
            assertTrue(e.message!!.contains("need 7 bytes"))
        }
    }

    @Test
    fun `InmotionI2Severity_of round-trips 0 through 3`() {
        for (code in 0..3) {
            assertEquals(code, InmotionI2Severity.of(code).code)
        }
    }
}
