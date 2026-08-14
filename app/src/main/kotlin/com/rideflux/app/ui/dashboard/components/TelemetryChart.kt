/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** A single line series rendered by [TelemetryChart]. */
data class ChartSeries(
    val label: String,
    val color: Color,
    /** Raw values; `null` entries are skipped (creates a gap). */
    val values: List<Float?>,
    /** If non-null, fixed minimum of the value axis. */
    val fixedMin: Float? = null,
    /** If non-null, fixed maximum of the value axis. */
    val fixedMax: Float? = null,
)

/**
 * Multi-series rolling line chart drawn on a [Canvas]. Each [ChartSeries]
 * is auto-scaled vertically to its own range (or to fixedMin/fixedMax
 * when provided) and rendered against a shared time axis given by
 * the index of each value in the list. New samples should be
 * appended to the end of the list; the leftmost sample is the
 * oldest.
 *
 * Note that auto-scaling each series to its own range makes series
 * with very different magnitudes look similar in amplitude; if
 * series need to be visually comparable, pass matching `fixedMin` /
 * `fixedMax` bounds instead.
 *
 * Designed to be lightweight: no animation, no interaction; the
 * parent re-composes when the underlying [series] changes.
 */
@Composable
fun TelemetryChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    // Cache per-series min/max/span so they are not recomputed on every
    // draw frame.
    val seriesStats = remember(series) {
        series.map { s ->
            // filterNotNull() alone keeps NaN/Infinity, which poison
            // min()/max() (Float.compare ranks NaN above everything)
            // and yield span = NaN/Infinity → NaN path coordinates.
            val nonNull = s.values.filterNotNull().filter { it.isFinite() }
            if (nonNull.isEmpty()) {
                null
            } else {
                val minV = s.fixedMin?.takeIf { it.isFinite() } ?: nonNull.min()
                val maxV = s.fixedMax?.takeIf { it.isFinite() } ?: nonNull.max()
                if (!minV.isFinite() || !maxV.isFinite() || minV > maxV) {
                    // Misconfigured fixed bounds; skip the series rather
                    // than rendering nonsense.
                    null
                } else {
                    SeriesStats(
                        nonNullCount = nonNull.size,
                        minV = minV,
                        // Zero-span data is centred vertically instead of
                        // being pinned to the axis minimum.
                        span = ((maxV - minV).takeIf { it > 0f && it.isFinite() } ?: 0f),
                    )
                }
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val strokePx = 3.dp.toPx()
            // ---- Grid lines (5 horizontal, 5 vertical incl. edges) -----
            val gridLines = 4
            for (i in 0..gridLines) {
                val y = h * i / gridLines
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                )
                val x = w * i / gridLines
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f,
                )
            }
            // ---- Plot each series independently -----------------------
            series.forEachIndexed { index, s ->
                val stats = seriesStats[index] ?: return@forEachIndexed
                val raw = s.values
                if (raw.size < 2) {
                    // A single isolated sample still deserves a marker.
                    val value = raw.firstOrNull() ?: return@forEachIndexed
                    drawSampleDot(value, stats, w, h, s.color)
                    return@forEachIndexed
                }

                val path = Path()
                var moved = false
                var lastX = 0f
                var lastY = 0f
                raw.forEachIndexed { idx, value ->
                    // Treat non-finite samples like gaps: a single NaN
                    // would otherwise flow into lineTo(NaN, NaN) and
                    // corrupt the whole path (coerceIn returns NaN).
                    if (value == null || !value.isFinite()) {
                        moved = false
                        return@forEachIndexed
                    }
                    val x = w * idx.toFloat() / (raw.size - 1).toFloat()
                    val y = normalizeY(value, stats, h)
                    if (!moved) {
                        path.moveTo(x, y)
                        moved = true
                    } else {
                        path.lineTo(x, y)
                    }
                    lastX = x
                    lastY = y
                }
                drawPath(
                    path = path,
                    color = s.color,
                    style = Stroke(width = strokePx),
                )
                // If only one non-null value exists (the rest are gaps) the
                // path has no lineTo, so render a visible dot at the sample.
                if (stats.nonNullCount == 1) {
                    drawDot(lastX, lastY, s.color)
                }
            }
        }
    }
}

/** Precomputed value-axis bounds for one series. */
private data class SeriesStats(
    val nonNullCount: Int,
    val minV: Float,
    val span: Float,
)

/** Normalise a raw value to the vertical position inside height [h]. */
private fun normalizeY(value: Float, stats: SeriesStats, h: Float): Float {
    val normalised = if (stats.span > 0f) {
        ((value - stats.minV) / stats.span).coerceIn(0f, 1f)
    } else {
        // Constant series: centre it so it doesn't hug the axis edge.
        0.5f
    }
    // Invert: high values render near top.
    return h - (h * normalised)
}

private fun DrawScope.drawSampleDot(
    value: Float,
    stats: SeriesStats,
    w: Float,
    h: Float,
    color: Color,
) {
    // Place a single sample at the right edge of the plot area.
    val radius = 4.dp.toPx()
    val x = w - radius
    val y = normalizeY(value, stats, h)
    drawCircle(color = color, radius = radius, center = Offset(x, y))
}

private fun DrawScope.drawDot(x: Float, y: Float, color: Color) {
    drawCircle(color = color, radius = 6.dp.toPx(), center = Offset(x, y))
}

/**
 * Inline legend chip for use beneath [TelemetryChart].
 */
@Composable
fun ChartLegend(series: List<ChartSeries>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(s.color),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    s.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
