/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.rideflux.app.ui.dashboard.components.RideFluxColors
import com.rideflux.app.ui.dashboard.components.SectionHeader
import com.rideflux.domain.ride.TripSample
import kotlin.math.cos

@Composable
fun MapPage(
    samples: List<TripSample>,
    locationPermissionGranted: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader("Route", accent = RideFluxColors.Cyan)
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(420.dp), contentAlignment = Alignment.Center) {
                TrackCanvas(samples = samples, modifier = Modifier.fillMaxSize(), showLiveMarker = true)
                if (!locationPermissionGranted) {
                    Text(
                        "Location permission not granted",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (samples.none { it.latitudeDeg != null && it.longitudeDeg != null }) {
                    Text("Waiting for a GPS fix…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            "Offline track preview • ${samples.count { it.latitudeDeg != null && it.longitudeDeg != null }} GPS points",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Offline route renderer shared by the live dashboard and saved-trip details. */
@Composable
fun TrackCanvas(
    samples: List<TripSample>,
    modifier: Modifier = Modifier,
    showLiveMarker: Boolean = false,
) {
    val points = remember(samples) {
        samples.mapNotNull { sample ->
            val lat = sample.latitudeDeg
            val lon = sample.longitudeDeg
            if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) GeoPoint(lat, lon) else null
        }
    }
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val routeColor = RideFluxColors.Cyan
    Canvas(modifier) {
        for (i in 0..8) {
            val x = size.width * i / 8f
            val y = size.height * i / 8f
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
        }
        if (points.isEmpty()) return@Canvas

        val meanLat = points.map(GeoPoint::latitude).average()
        val lonScale = cos(Math.toRadians(meanLat)).coerceAtLeast(0.01)
        val projected = points.map { Offset((it.longitude * lonScale).toFloat(), it.latitude.toFloat()) }
        val minX = projected.minOf(Offset::x)
        val maxX = projected.maxOf(Offset::x)
        val minY = projected.minOf(Offset::y)
        val maxY = projected.maxOf(Offset::y)
        val spanX = (maxX - minX).coerceAtLeast(0.000001f)
        val spanY = (maxY - minY).coerceAtLeast(0.000001f)
        val pad = 24.dp.toPx()
        val usableW = (size.width - pad * 2).coerceAtLeast(1f)
        val usableH = (size.height - pad * 2).coerceAtLeast(1f)
        fun normalise(p: Offset) = Offset(
            pad + (p.x - minX) / spanX * usableW,
            size.height - pad - (p.y - minY) / spanY * usableH,
        )

        val canvasPoints = projected.map(::normalise)
        if (canvasPoints.size > 1) {
            val path = Path().apply {
                moveTo(canvasPoints.first().x, canvasPoints.first().y)
                canvasPoints.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, routeColor, style = Stroke(width = 4.dp.toPx()))
        }
        drawCircle(RideFluxColors.Neon, 7.dp.toPx(), canvasPoints.first())
        drawCircle(RideFluxColors.Danger, 7.dp.toPx(), canvasPoints.last())
        if (showLiveMarker) {
            drawCircle(RideFluxColors.DeepBlue, 4.dp.toPx(), canvasPoints.last())
        }
    }
}

private data class GeoPoint(val latitude: Double, val longitude: Double)
