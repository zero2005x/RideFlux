/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rideflux.app.ui.dashboard.TelemetrySample
import com.rideflux.app.ui.dashboard.components.ChartLegend
import com.rideflux.app.ui.dashboard.components.ChartSeries
import com.rideflux.app.ui.dashboard.components.RideFluxColors
import com.rideflux.app.ui.dashboard.components.TelemetryChart

/**
 * Page 2: Telemetry graph.
 *
 * Renders a stacked pair of [TelemetryChart]s. The top chart plots
 * speed and battery on a 0..100 scale (battery directly, speed
 * mapped 0..80 → 0..100). The bottom chart plots current, MOS
 * temperature and voltage, each auto-scaled per series.
 */
@Composable
fun GraphPage(history: List<TelemetrySample>, useMetric: Boolean = true, modifier: Modifier = Modifier) {
    val speed = remember(history, useMetric) {
        history.map { sample -> sample.speedKmh?.let { if (useMetric) it else it * 0.6213712f } }
    }
    val battery = remember(history) { history.map { it.batteryPercent } }
    val voltage = remember(history) { history.map { it.voltageV } }
    val current = remember(history) { history.map { it.currentA } }
    val mos = remember(history) { history.map { it.mosTemperatureC } }
    val speedAxisMax = remember(speed) {
        maxOf(80f, speed.filterNotNull().filter { it.isFinite() }.maxOrNull() ?: 0f)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "TELEMETRY",
            color = RideFluxColors.Cyan,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )

        if (history.isEmpty()) {
            Text(
                text = "Waiting for telemetry samples…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        // ---- Chart A: speed + battery -----------------------------------
        val seriesA = listOf(
            ChartSeries(
                label = if (useMetric) "Speed (km/h)" else "Speed (mph)",
                color = RideFluxColors.Cyan,
                values = speed,
                fixedMin = 0f,
                fixedMax = speedAxisMax,
            ),
            ChartSeries(
                label = "Battery (%)",
                color = RideFluxColors.Neon,
                values = battery,
                fixedMin = 0f,
                fixedMax = 100f,
            ),
        )
        TelemetryChart(
            series = seriesA,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        )
        ChartLegend(seriesA)

        Spacer(Modifier.height(4.dp))

        // ---- Chart B: current + MOS temperature + voltage ----------------
        val seriesB = listOf(
            ChartSeries(
                label = "Current (A)",
                color = RideFluxColors.Warning,
                values = current,
            ),
            ChartSeries(
                label = "MOS (°C)",
                color = RideFluxColors.Danger,
                values = mos,
            ),
            ChartSeries(
                label = "Voltage (V)",
                color = RideFluxColors.DeepBlue,
                values = voltage,
            ),
        )
        TelemetryChart(
            series = seriesB,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        )
        ChartLegend(seriesB)
    }
}
