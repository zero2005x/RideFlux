/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard.components

import androidx.compose.ui.graphics.Color

/**
 * Extra accent palette used by the multi-page dashboard.
 *
 * `MaterialTheme.colorScheme` already gives us `primary` (electric
 * cyan), `error` (red) and `tertiary` (orange) — these tokens add
 * the rest of the riding-cockpit palette so that every page can
 * reach them without redefining `Color(0x…)` literals inline:
 *
 *  * [Cyan]      — speed / primary live values
 *  * [Neon]      — battery / "good" line on the chart
 *  * [Warning]   — caution thresholds (current spike, MOS climb)
 *  * [Danger]    — critical thresholds (overtemp, low battery cutoff)
 *  * [DeepBlue]  — graph fills, secondary section headers
 *  * [Mute]      — chart grid lines / inactive page indicators
 */
object RideFluxColors {
    val Cyan: Color = Color(0xFF00E5FF)
    val Neon: Color = Color(0xFF39FF8F)
    val Warning: Color = Color(0xFFFFB300)
    val Danger: Color = Color(0xFFFF5252)
    val DeepBlue: Color = Color(0xFF0D47A1)
    val Mute: Color = Color(0xFF37474F)
    val OnDark: Color = Color(0xFFE0F7FA)
    val SurfaceElev: Color = Color(0xFF101820)
}

/**
 * Stop-light tinting used by every "live value" tile: pick a colour
 * appropriate to where [value] sits in the [warn]..[danger] range,
 * so the dashboard surfaces severity at a glance.
 *
 * Both thresholds are inclusive lower bounds for entering the next
 * tier. Pass [Float.POSITIVE_INFINITY] for whichever tier is not
 * meaningful for a given metric.
 */
fun stoplight(
    value: Float?,
    warn: Float,
    danger: Float,
    nominal: Color = RideFluxColors.Cyan,
): Color {
    require(warn.isFinite() && danger.isFinite() && warn <= danger) {
        "warn ($warn) and danger ($danger) must be finite, with warn <= danger"
    }
    if (value == null || value.isNaN() || value == Float.NEGATIVE_INFINITY) {
        return RideFluxColors.Mute
    }
    if (value == Float.POSITIVE_INFINITY) return RideFluxColors.Danger
    return when {
        value >= danger -> RideFluxColors.Danger
        value >= warn -> RideFluxColors.Warning
        else -> nominal
    }
}
