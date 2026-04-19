/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---- Palette -----------------------------------------------------------
//
// RideFlux uses a high-contrast dark palette by default: near-black
// backgrounds keep the HUD legible at night / in a helmet mirror, and
// a saturated cyan accent pops against the dark surface for primary
// data points (speed, battery). Warning/error tones are kept punchy
// so AlertBanner remains unmissable.
private val RideFluxDark = darkColorScheme(
    primary = Color(0xFF00E5FF),          // electric cyan
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF6FF6FF),
    secondary = Color(0xFFB0BEC5),
    onSecondary = Color(0xFF1C313A),
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE0F7FA),
    surface = Color(0xFF0A0F14),
    onSurface = Color(0xFFE0F7FA),
    surfaceVariant = Color(0xFF14202B),
    onSurfaceVariant = Color(0xFFB0BEC5),
    outline = Color(0xFF37474F),
    error = Color(0xFFFF5252),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF4A0000),
    onErrorContainer = Color(0xFFFFB4A9),
)

private val RideFluxLight = lightColorScheme(
    primary = Color(0xFF006875),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF4A6267),
    tertiary = Color(0xFFE65100),
    background = Color(0xFFF5FAFA),
    onBackground = Color(0xFF001F24),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF001F24),
    error = Color(0xFFBA1A1A),
)

/**
 * Typography skewed toward very large, bold numerals for the main
 * telemetry value (speed). Uses the system default font family to
 * keep the APK lean.
 */
private val RideFluxTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 128.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-4).sp,
    ),
    displayMedium = TextStyle(
        fontSize = 56.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
)

/**
 * Root theme for the RideFlux app. Defaults to dark for HUD-style
 * dashboards but adapts to the system setting.
 */
@Composable
fun RideFluxTheme(
    darkTheme: Boolean = isSystemInDarkTheme() || true, // prefer dark for HUD
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) RideFluxDark else RideFluxLight
    MaterialTheme(
        colorScheme = colors,
        typography = RideFluxTypography,
        content = content,
    )
}
