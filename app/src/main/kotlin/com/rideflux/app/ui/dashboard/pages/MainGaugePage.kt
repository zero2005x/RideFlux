/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rideflux.app.ui.dashboard.DashboardUiState
import com.rideflux.app.ui.dashboard.displayDistance
import com.rideflux.app.ui.dashboard.displaySpeed
import com.rideflux.app.ui.dashboard.distanceUnit
import com.rideflux.app.ui.dashboard.speedUnit
import com.rideflux.app.ui.dashboard.components.BatteryBar
import com.rideflux.app.ui.dashboard.components.MetricCard
import com.rideflux.app.ui.dashboard.components.MetricRow
import com.rideflux.app.ui.dashboard.components.RideFluxColors
import com.rideflux.app.ui.dashboard.components.SpeedGauge
import com.rideflux.app.ui.dashboard.components.stoplight
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Page 1: Main riding dashboard.
 *
 * Layout from top to bottom:
 *  - Centred half-disc speed gauge with digital readout in the bore
 *  - Battery bar with percent + voltage
 *  - 3×2 grid of secondary stats (current, power, MOS temp, trip,
 *    total, battery)
 *
 * Designed to be glanceable while riding: each value owns at least
 * 30dp of vertical real estate and is colour-coded via [stoplight].
 */
@Composable
fun MainGaugePage(state: DashboardUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SpeedGauge(
            speedKmh = state.displaySpeed(state.speedKmh),
            maxKmh = if (state.useMetric) 80f else 50f,
            redlineKmh = if (state.useMetric) 60f else 37f,
            unitLabel = state.speedUnit,
        )

        // ---- Battery row ----------------------------------------------
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.batteryPercent
                        ?.let { "${it.roundToInt()}%" } ?: "--%",
                    style = MaterialTheme.typography.titleLarge,
                    color = stoplight(
                        value = state.batteryPercent?.let { 100f - it },
                        warn = 70f,
                        danger = 85f,
                        nominal = RideFluxColors.Neon,
                    ),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = state.voltageV?.let { "%.1f V".format(Locale.US, it) } ?: "-- V",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            BatteryBar(percent = state.batteryPercent)
        }

        // ---- 2×2 secondary stats grid ---------------------------------
        MetricRow(
            left = {
                MetricCard(
                    label = "Current",
                    icon = Icons.Filled.Bolt,
                    value = state.currentA?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = "A",
                    valueColor = stoplight(
                        value = state.currentA?.let { kotlin.math.abs(it) },
                        warn = 30f,
                        danger = 60f,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Power",
                    icon = Icons.Filled.ElectricBolt,
                    value = state.powerW?.let { "%.0f".format(Locale.US, it) } ?: "--",
                    unit = "W",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = "MOS Temp",
                    icon = Icons.Filled.Thermostat,
                    value = state.mosTemperatureC?.let { "%.0f".format(Locale.US, it) } ?: "--",
                    unit = "°C",
                    valueColor = stoplight(
                        value = state.mosTemperatureC,
                        warn = 60f,
                        danger = 75f,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Trip",
                    icon = Icons.Filled.Speed,
                    value = state.displayDistance(state.tripDistanceMetres)
                        ?.let { "%.2f".format(Locale.US, it) } ?: "--",
                    unit = state.distanceUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = "Total",
                    icon = Icons.Filled.Speed,
                    value = state.displayDistance(state.totalDistanceMetres)
                        ?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = state.distanceUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Battery",
                    icon = Icons.Filled.BatteryFull,
                    value = state.batteryPercent?.let { "${it.roundToInt()}" } ?: "--",
                    unit = "%",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}
