/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudLayoutScalingTest {

    @Test
    fun `rv101 baseline dimensions produce 1x scale and proportional layout`() {
        val dims = HudLayoutDimensions.calculate(
            widthDp = HudLayoutDimensions.BASELINE_WIDTH_DP,
            heightDp = HudLayoutDimensions.BASELINE_HEIGHT_DP,
        )
        assertEquals(1.0f, dims.scale, 0.01f)
        assertEquals(72f, dims.speedFontSizeSp, 0.5f)
        assertEquals(20f, dims.clockFontSizeSp, 0.5f)
        assertEquals(22f, dims.batteryFontSizeSp, 0.5f)
        assertEquals(14f, dims.labelFontSizeSp, 0.5f)
        assertEquals(16f, dims.iconSizeDp, 0.5f)
        assertTrue("Side column should fit RV101 bounds", dims.sideColumnWidthDp in 85f..90f)
        assertTrue("Center column should fit RV101 bounds", dims.centerColumnWidthDp in 120f..130f)
    }

    @Test
    fun `wide landscape ar glasses scale up cleanly`() {
        val dims = HudLayoutDimensions.calculate(widthDp = 640f, heightDp = 360f)
        assertTrue("Scale should be larger than baseline", dims.scale > 1.0f)
        assertTrue("Speed font size should scale up", dims.speedFontSizeSp > 72f)
        assertTrue("Icon size should scale up", dims.iconSizeDp > 16f)
        // Total column width plus padding should not exceed total width
        val totalContentWidth = (dims.sideColumnWidthDp * 2) + dims.centerColumnWidthDp + (dims.horizontalPaddingDp * 2)
        assertTrue("Total content width $totalContentWidth fits within 640 dp", totalContentWidth <= 640f)
    }

    @Test
    fun `small viewport is clamped to minimum limits`() {
        val dims = HudLayoutDimensions.calculate(widthDp = 200f, heightDp = 150f)
        assertEquals(0.75f, dims.scale, 0.01f)
        assertTrue(dims.speedFontSizeSp >= 72f * 0.75f)
        assertTrue(dims.iconSizeDp >= 14f)
        assertTrue(dims.sideColumnWidthDp >= 75f)
    }
}
