/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Half-disc speedometer rendered to a [Canvas]. Wheellog-style:
 * the gauge sweeps from -210° to +30° (240° arc) and shades from
 * cyan → orange → red as the rider approaches the configured
 * red-line.
 *
 * The composable is intentionally pure: it accepts a current speed
 * and the scale's full-scale value and draws everything inside the
 * canvas. No animation: the parent recomposes when [speedKmh]
 * changes.
 *
 * @param speedKmh current speed (km/h); `null` renders an empty dial
 * @param maxKmh   full-scale value at the right end of the sweep
 * @param redlineKmh speed at which the arc tint flips to [RideFluxColors.Danger]
 */
@Composable
fun SpeedGauge(
    speedKmh: Float?,
    modifier: Modifier = Modifier,
    maxKmh: Float = 80f,
    redlineKmh: Float = 60f,
    unitLabel: String = "km/h",
) {
    // Sanitize non-finite inputs: coerceAtLeast/coerceIn do NOT filter
    // NaN (every comparison with NaN is false, so NaN passes through
    // unchanged into drawArc/drawCircle geometry). Math.round(NaN) == 0
    // would also make a bad sample silently display as "0".
    val safeMax = maxKmh.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    val safeSpeed = speedKmh?.takeIf { it.isFinite() }?.coerceIn(0f, safeMax) ?: 0f
    val redline = redlineKmh.takeIf { it.isFinite() }?.coerceIn(0f, safeMax) ?: safeMax
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val arcTint = when {
        safeSpeed >= redline -> RideFluxColors.Danger
        safeSpeed >= redline * 0.75f -> RideFluxColors.Warning
        else -> RideFluxColors.Cyan
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .padding(12.dp),
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h * 0.66f
            val radius = (kotlin.math.min(w / 2f, h * 0.66f) - 24f).coerceAtLeast(1f)

            // ---- Outer ambient arc (track) ---------------------------
            drawArc(
                color = RideFluxColors.Mute.copy(alpha = 0.4f),
                startAngle = 150f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 18f),
            )

            // ---- Active arc gradient --------------------------------
            val ratio = safeSpeed / safeMax
            val activeSweep = 240f * ratio
            drawArc(
                brush = Brush.sweepGradient(
                    colorStops = arrayOf(
                        30f / 360f to RideFluxColors.Danger,
                        150f / 360f to RideFluxColors.Cyan,
                        306f / 360f to RideFluxColors.Warning,
                    ),
                    center = Offset(cx, cy),
                ),
                startAngle = 150f,
                sweepAngle = activeSweep,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 18f),
            )

            // ---- Tick marks -----------------------------------------
            val tickCount = 12
            for (i in 0..tickCount) {
                val angleDeg = 150f + (240f * i / tickCount)
                val angleRad = angleDeg * (PI / 180f).toFloat()
                val major = i % 2 == 0
                val inner = radius - if (major) 30f else 18f
                val outer = radius - 4f
                val sx = cx + cos(angleRad) * inner
                val sy = cy + sin(angleRad) * inner
                val ex = cx + cos(angleRad) * outer
                val ey = cy + sin(angleRad) * outer
                drawLine(
                    color = tickColor,
                    start = Offset(sx, sy),
                    end = Offset(ex, ey),
                    strokeWidth = if (major) 4f else 2f,
                )
            }

            // ---- Pointer / tip dot for the live value ---------------
            val pointerAngleRad = (150f + activeSweep) * (PI / 180f).toFloat()
            val tipX = cx + cos(pointerAngleRad) * (radius - 4f)
            val tipY = cy + sin(pointerAngleRad) * (radius - 4f)
            drawCircle(
                color = arcTint,
                radius = 10f,
                center = Offset(tipX, tipY),
            )
            drawCircle(
                color = arcTint.copy(alpha = 0.25f),
                radius = 22f,
                center = Offset(tipX, tipY),
            )
        }

        // Centre digital readout, layered on top of the canvas.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (speedKmh?.isFinite() == true) {
                    safeSpeed.roundToInt().toString()
                } else {
                    "--"
                },
                color = arcTint,
                fontSize = 96.sp,
                fontWeight = FontWeight.Black,
                style = androidx.compose.ui.text.TextStyle(letterSpacing = (-4).sp),
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = unitLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Compact horizontal battery bar with percentage label, suitable
 * for the secondary stat row beneath the speed gauge.
 */
@Composable
fun BatteryBar(
    percent: Float?,
    modifier: Modifier = Modifier,
) {
    val clamped = percent?.takeIf { it.isFinite() }?.coerceIn(0f, 100f)
    val tint = when {
        clamped == null -> RideFluxColors.Mute
        clamped <= 15f -> RideFluxColors.Danger
        clamped <= 30f -> RideFluxColors.Warning
        else -> RideFluxColors.Neon
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp),
    ) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = RideFluxColors.Mute.copy(alpha = 0.3f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f),
        )
        if (clamped != null) {
            drawRoundRect(
                color = tint,
                size = Size(w * (clamped / 100f), h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f),
            )
        }
    }
}

/**
 * Helper used by the page indicator. Returns a colour with the
 * given [alpha] applied multiplicatively, preserving the input's
 * existing alpha channel.
 */
internal fun Color.scaleAlpha(alpha: Float): Color =
    this.copy(alpha = this.alpha * alpha)
