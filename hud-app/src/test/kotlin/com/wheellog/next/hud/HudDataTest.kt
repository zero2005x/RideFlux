package com.wheellog.next.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudDataTest {

    // ═══════════════════════════════════════════════════════════════════
    //  Default values
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `default HudData has zero values and useMetric true`() {
        val data = HudData()
        assertEquals(0f, data.speedKmh, 0f)
        assertEquals(0, data.batteryPercent)
        assertEquals(0f, data.temperatureC, 0f)
        assertEquals(0, data.alertMask)
        assertTrue(data.useMetric)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Individual alert flags
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `hasOverspeed true when bit 0 set`() {
        assertTrue(HudData(alertMask = 0x01).hasOverspeed)
        assertTrue(HudData(alertMask = 0x0F).hasOverspeed)
    }

    @Test
    fun `hasOverspeed false when bit 0 clear`() {
        assertFalse(HudData(alertMask = 0x00).hasOverspeed)
        assertFalse(HudData(alertMask = 0x0E).hasOverspeed)
    }

    @Test
    fun `hasLowBattery true when bit 1 set`() {
        assertTrue(HudData(alertMask = 0x02).hasLowBattery)
        assertTrue(HudData(alertMask = 0x0F).hasLowBattery)
    }

    @Test
    fun `hasLowBattery false when bit 1 clear`() {
        assertFalse(HudData(alertMask = 0x00).hasLowBattery)
        assertFalse(HudData(alertMask = 0x0D).hasLowBattery)
    }

    @Test
    fun `hasHighTemperature true when bit 2 set`() {
        assertTrue(HudData(alertMask = 0x04).hasHighTemperature)
        assertTrue(HudData(alertMask = 0x0F).hasHighTemperature)
    }

    @Test
    fun `hasHighTemperature false when bit 2 clear`() {
        assertFalse(HudData(alertMask = 0x00).hasHighTemperature)
        assertFalse(HudData(alertMask = 0x0B).hasHighTemperature)
    }

    @Test
    fun `hasTiltBack true when bit 3 set`() {
        assertTrue(HudData(alertMask = 0x08).hasTiltBack)
        assertTrue(HudData(alertMask = 0x0F).hasTiltBack)
    }

    @Test
    fun `hasTiltBack false when bit 3 clear`() {
        assertFalse(HudData(alertMask = 0x00).hasTiltBack)
        assertFalse(HudData(alertMask = 0x07).hasTiltBack)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  hasAnyAlert
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `hasAnyAlert true when any bit set`() {
        assertTrue(HudData(alertMask = 0x01).hasAnyAlert)
        assertTrue(HudData(alertMask = 0x08).hasAnyAlert)
        assertTrue(HudData(alertMask = 0xFF).hasAnyAlert)
    }

    @Test
    fun `hasAnyAlert false when no bits set`() {
        assertFalse(HudData(alertMask = 0).hasAnyAlert)
    }

    @Test
    fun `hasAnyAlert true for high bits beyond defined flags`() {
        // Bits above bit 3 are not defined flags, but alertMask != 0 should still mean hasAnyAlert
        assertTrue(HudData(alertMask = 0x10).hasAnyAlert)
        assertTrue(HudData(alertMask = 0x80).hasAnyAlert)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Alert flag isolation — each flag is independent
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `only OVERSPEED flag set - other flags are false`() {
        val data = HudData(alertMask = 0x01)
        assertTrue(data.hasOverspeed)
        assertFalse(data.hasLowBattery)
        assertFalse(data.hasHighTemperature)
        assertFalse(data.hasTiltBack)
    }

    @Test
    fun `only LOW_BATTERY flag set - other flags are false`() {
        val data = HudData(alertMask = 0x02)
        assertFalse(data.hasOverspeed)
        assertTrue(data.hasLowBattery)
        assertFalse(data.hasHighTemperature)
        assertFalse(data.hasTiltBack)
    }

    @Test
    fun `only HIGH_TEMPERATURE flag set - other flags are false`() {
        val data = HudData(alertMask = 0x04)
        assertFalse(data.hasOverspeed)
        assertFalse(data.hasLowBattery)
        assertTrue(data.hasHighTemperature)
        assertFalse(data.hasTiltBack)
    }

    @Test
    fun `only TILT_BACK flag set - other flags are false`() {
        val data = HudData(alertMask = 0x08)
        assertFalse(data.hasOverspeed)
        assertFalse(data.hasLowBattery)
        assertFalse(data.hasHighTemperature)
        assertTrue(data.hasTiltBack)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  useMetric field
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `useMetric defaults to true`() {
        assertTrue(HudData().useMetric)
    }

    @Test
    fun `useMetric can be set to false`() {
        assertFalse(HudData(useMetric = false).useMetric)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Data class equality and copy
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `data class equals for identical values`() {
        val a = HudData(speedKmh = 50f, batteryPercent = 80, temperatureC = 35f, alertMask = 0x03, useMetric = true)
        val b = HudData(speedKmh = 50f, batteryPercent = 80, temperatureC = 35f, alertMask = 0x03, useMetric = true)
        assertEquals(a, b)
    }

    @Test
    fun `data class not equals for different speed`() {
        val a = HudData(speedKmh = 50f)
        val b = HudData(speedKmh = 51f)
        assertNotEquals(a, b)
    }

    @Test
    fun `data class not equals for different alertMask`() {
        val a = HudData(alertMask = 0x01)
        val b = HudData(alertMask = 0x02)
        assertNotEquals(a, b)
    }

    @Test
    fun `data class copy preserves unchanged fields`() {
        val original = HudData(speedKmh = 60f, batteryPercent = 90, temperatureC = 30f, alertMask = 0x03, useMetric = false)
        val copied = original.copy(speedKmh = 70f)

        assertEquals(70f, copied.speedKmh, 0f)
        assertEquals(90, copied.batteryPercent)
        assertEquals(30f, copied.temperatureC, 0f)
        assertEquals(0x03, copied.alertMask)
        assertFalse(copied.useMetric)
    }

    @Test
    fun `data class copy can change multiple fields`() {
        val original = HudData()
        val copied = original.copy(speedKmh = 100f, alertMask = 0x0F)

        assertEquals(100f, copied.speedKmh, 0f)
        assertEquals(0x0F, copied.alertMask)
        assertEquals(0, copied.batteryPercent) // unchanged
    }
}
