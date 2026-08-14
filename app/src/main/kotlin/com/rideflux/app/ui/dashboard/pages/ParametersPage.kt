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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rideflux.app.ui.dashboard.DashboardUiState
import com.rideflux.app.ui.dashboard.displayDistance
import com.rideflux.app.ui.dashboard.displaySpeed
import com.rideflux.app.ui.dashboard.distanceUnit
import com.rideflux.app.ui.dashboard.speedUnit
import com.rideflux.app.ui.dashboard.components.MetricCard
import com.rideflux.app.ui.dashboard.components.MetricRow
import com.rideflux.app.ui.dashboard.components.RideFluxColors
import com.rideflux.app.ui.dashboard.components.SectionHeader
import com.rideflux.app.ui.dashboard.components.stoplight
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Page 3: parameters grid.
 *
 * Dense two-column overview of every numeric value the codec
 * surfaces. Sections separate the values by domain (kinematics →
 * power → thermals → device). Empty / unavailable values fall back
 * to "--" instead of disappearing so the layout stays stable across
 * the connection lifecycle.
 */
@Composable
fun ParametersPage(state: DashboardUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader("Speed", accent = RideFluxColors.Cyan)
        MetricRow(
            left = {
                MetricCard(
                    label = "Speed",
                    value = state.displaySpeed(state.speedKmh)?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = state.speedUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Max",
                    value = state.displaySpeed(state.maxSpeedKmh)?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = state.speedUnit,
                    valueColor = stoplight(state.maxSpeedKmh, warn = 45f, danger = 60f),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = "Average",
                    value = state.displaySpeed(state.avgSpeedKmh)?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = state.speedUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "PWM",
                    value = state.pwmPercent?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = "%",
                    valueColor = stoplight(state.pwmPercent, warn = 80f, danger = 90f),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        SectionHeader("Power", accent = RideFluxColors.Warning)
        MetricRow(
            left = {
                MetricCard(
                    label = "Voltage",
                    value = state.voltageV?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = "V",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Current",
                    value = state.currentA?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = "A",
                    valueColor = stoplight(
                        value = state.currentA?.let { kotlin.math.abs(it) },
                        warn = 30f, danger = 60f,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = "Phase Current",
                    value = state.phaseCurrentA?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = "A",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Power",
                    value = state.powerW?.let { "%.0f".format(Locale.US, it) } ?: "--",
                    unit = "W",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = "Battery",
                    value = state.batteryPercent?.let { "${it.roundToInt()}" } ?: "--",
                    unit = "%",
                    valueColor = stoplight(
                        value = state.batteryPercent?.let { 100f - it },
                        warn = 70f,
                        danger = 85f,
                        nominal = RideFluxColors.Neon,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Battery Voltage",
                    value = state.batteryVoltageV?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = "V",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        SectionHeader("Thermals", accent = RideFluxColors.Danger)
        MetricRow(
            left = {
                MetricCard(
                    label = "MOS",
                    value = state.mosTemperatureC?.let { "%.0f".format(Locale.US, it) } ?: "--",
                    unit = "°C",
                    valueColor = stoplight(state.mosTemperatureC, warn = 60f, danger = 75f),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Motor",
                    value = state.motorTemperatureC?.let { "%.0f".format(Locale.US, it) } ?: "--",
                    unit = "°C",
                    valueColor = stoplight(state.motorTemperatureC, warn = 70f, danger = 90f),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = "Battery Temp",
                    value = state.batteryTemperatureC?.let { "%.0f".format(Locale.US, it) } ?: "--",
                    unit = "°C",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Board",
                    value = state.boardTemperatureC?.let { "%.0f".format(Locale.US, it) } ?: "--",
                    unit = "°C",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        SectionHeader("Distance", accent = RideFluxColors.Neon)
        MetricRow(
            left = {
                MetricCard(
                    label = "Trip",
                    value = state.displayDistance(state.tripDistanceMetres)
                        ?.let { "%.2f".format(Locale.US, it) } ?: "--",
                    unit = state.distanceUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Total",
                    value = state.displayDistance(state.totalDistanceMetres)
                        ?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = state.distanceUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = "Ride Time",
                    value = formatDuration(state.rideTimeSeconds),
                    unit = "h:m:s",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Mode",
                    value = state.rideMode?.label ?: "--",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        SectionHeader("Device", accent = RideFluxColors.Cyan)
        MetricRow(
            left = {
                MetricCard(
                    label = "Model",
                    value = state.identity?.modelName ?: "--",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Family",
                    value = state.identity?.family?.name ?: "--",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = "Firmware",
                    value = state.identity?.firmwareVersion ?: "--",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Address",
                    value = state.identity?.address ?: "--",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}

/** Format a duration in seconds as "H:MM:SS" / "M:SS". Clamps negative input. */
internal fun formatDuration(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0)
    val s = safeSeconds % 60
    val m = (safeSeconds / 60) % 60
    val h = safeSeconds / 3600
    return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s)
    else "%d:%02d".format(Locale.US, m, s)
}
