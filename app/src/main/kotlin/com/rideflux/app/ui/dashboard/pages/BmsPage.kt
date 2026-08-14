/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rideflux.app.ui.dashboard.DashboardUiState
import com.rideflux.app.ui.dashboard.components.MetricCard
import com.rideflux.app.ui.dashboard.components.MetricRow
import com.rideflux.app.ui.dashboard.components.RideFluxColors
import com.rideflux.app.ui.dashboard.components.SectionHeader
import com.rideflux.app.ui.dashboard.components.stoplight
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Page 4: Smart BMS / battery detail.
 *
 * Most current EUC families don't expose per-cell voltage on their
 * BLE telemetry — those frames are reserved for the wheel's
 * internal smart-BMS link. We therefore render whatever pack-level
 * fields *are* available and surface a clear placeholder for the
 * per-cell list rather than fabricating values.
 */
@Composable
fun BmsPage(state: DashboardUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader("Pack", accent = RideFluxColors.Neon)
        MetricRow(
            left = {
                MetricCard(
                    label = "State of Charge",
                    // Guard non-finite telemetry before roundToInt(),
                    // which throws IllegalArgumentException for NaN / ±∞.
                    value = state.batteryPercent?.takeIf { it.isFinite() }?.let { "${it.roundToInt()}" } ?: "--",
                    unit = "%",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Voltage",
                    // Filter NaN/Infinity so the tile shows "--" instead
                    // of the literal strings "NaN V" / "Infinity V".
                    value = state.voltageV?.takeIf { it.isFinite() }?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = "V",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = "Current",
                    value = state.currentA?.takeIf { it.isFinite() }?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = "A",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Power",
                    value = state.powerW?.takeIf { it.isFinite() }?.let { "%.0f".format(Locale.US, it) } ?: "--",
                    unit = "W",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        SectionHeader("Thermals", accent = RideFluxColors.Danger)
        MetricRow(
            left = {
                MetricCard(
                    label = "Battery Temp",
                    value = state.batteryTemperatureC?.takeIf { it.isFinite() }?.let { "%.0f".format(Locale.US, it) } ?: "--",
                    unit = "°C",
                    valueColor = stoplight(state.batteryTemperatureC, warn = 50f, danger = 60f),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "MOS Temp",
                    value = state.mosTemperatureC?.takeIf { it.isFinite() }?.let { "%.0f".format(Locale.US, it) } ?: "--",
                    unit = "°C",
                    valueColor = stoplight(state.mosTemperatureC, warn = 60f, danger = 75f),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        SectionHeader("Cells", accent = RideFluxColors.Cyan)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Per-cell data",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "This wheel does not expose individual cell voltages over " +
                        "its BLE telemetry link. Smart-BMS data is only " +
                        "available on builds that wire the BMS UART through " +
                        "the controller — none of the connected family does.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
