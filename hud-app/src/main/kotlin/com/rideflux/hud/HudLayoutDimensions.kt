/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud

/**
 * Responsive layout calculation for the glasses AR HUD.
 *
 * Replaces fixed 320 dp RV101 viewport assumptions with dynamic
 * column scaling and typography calculation based on actual display
 * constraints from `BoxWithConstraints`.
 */
data class HudLayoutDimensions(
    val scale: Float,
    val sideColumnWidthDp: Float,
    val centerColumnWidthDp: Float,
    val horizontalPaddingDp: Float,
    val topPaddingDp: Float,
    val bottomPaddingDp: Float,
    val speedFontSizeSp: Float,
    val speedUnitFontSizeSp: Float,
    val clockFontSizeSp: Float,
    val batteryFontSizeSp: Float,
    val labelFontSizeSp: Float,
    val iconSizeDp: Float,
    val verticalSpacingDp: Float,
) {
    companion object {
        const val BASELINE_WIDTH_DP = 320f
        const val BASELINE_HEIGHT_DP = 426f

        /**
         * Calculates responsive HUD dimension tokens from available viewport width and height in dp.
         */
        fun calculate(widthDp: Float, heightDp: Float): HudLayoutDimensions {
            val effectiveWidth = widthDp.coerceAtLeast(240f)
            val effectiveHeight = heightDp.coerceAtLeast(200f)

            val widthScale = effectiveWidth / BASELINE_WIDTH_DP
            val heightScale = effectiveHeight / BASELINE_HEIGHT_DP
            val scale = minOf(widthScale, heightScale * 1.25f).coerceIn(0.75f, 3.5f)

            val horizontalPadding = (effectiveWidth * 0.02f).coerceIn(4f, 24f)
            val usableWidth = effectiveWidth - (horizontalPadding * 2f)
            val sideWidth = (usableWidth * 0.285f).coerceIn(75f, 350f)
            val centerWidth = (usableWidth * 0.41f).coerceIn(110f, 600f)

            val topPadding = (effectiveHeight * 0.08f).coerceIn(16f, 64f)
            val bottomPadding = (effectiveHeight * 0.05f).coerceIn(12f, 48f)

            return HudLayoutDimensions(
                scale = scale,
                sideColumnWidthDp = sideWidth,
                centerColumnWidthDp = centerWidth,
                horizontalPaddingDp = horizontalPadding,
                topPaddingDp = topPadding,
                bottomPaddingDp = bottomPadding,
                speedFontSizeSp = 72f * scale,
                speedUnitFontSizeSp = (16f * scale).coerceAtLeast(12f),
                clockFontSizeSp = 20f * scale,
                batteryFontSizeSp = 22f * scale,
                labelFontSizeSp = (14f * scale).coerceAtLeast(11f),
                iconSizeDp = (16f * scale).coerceIn(14f, 48f),
                verticalSpacingDp = (8f * scale).coerceIn(6f, 24f),
            )
        }
    }
}
