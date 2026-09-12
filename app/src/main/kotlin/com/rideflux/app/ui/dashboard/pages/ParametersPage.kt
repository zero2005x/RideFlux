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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rideflux.app.R
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
 *
 * Labels come from string resources; the numbers themselves stay on
 * [Locale.US] so the decimal separator matches the ASCII unit next to
 * them and the two columns keep a stable width across locales.
 */
@Composable
fun ParametersPage(state: DashboardUiState, modifier: Modifier = Modifier) {
    val dash = stringResource(R.string.value_unavailable)
    val percent = stringResource(R.string.unit_percent)
    val volt = stringResource(R.string.unit_volt)
    val ampere = stringResource(R.string.unit_ampere)
    val watt = stringResource(R.string.unit_watt)
    val celsius = stringResource(R.string.unit_celsius)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(stringResource(R.string.section_speed), accent = RideFluxColors.Cyan)
        MetricRow(
            left = {
                MetricCard(
                    label = stringResource(R.string.metric_speed),
                    value = state.displaySpeed(state.speedKmh)?.let { "%.1f".format(Locale.US, it) } ?: dash,
                    unit = state.speedUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = stringResource(R.string.metric_max),
                    value = state.displaySpeed(state.maxSpeedKmh)?.let { "%.1f".format(Locale.US, it) } ?: dash,
                    unit = state.speedUnit,
                    valueColor = stoplight(state.maxSpeedKmh, warn = 45f, danger = 60f),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = stringResource(R.string.metric_average),
                    value = state.displaySpeed(state.avgSpeedKmh)?.let { "%.1f".format(Locale.US, it) } ?: dash,
                    unit = state.speedUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = stringResource(R.string.metric_pwm),
                    value = state.pwmPercent?.let { "%.1f".format(Locale.US, it) } ?: dash,
                    unit = percent,
                    valueColor = stoplight(state.pwmPercent, warn = 80f, danger = 90f),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        SectionHeader(stringResource(R.string.section_power), accent = RideFluxColors.Warning)
        MetricRow(
            left = {
                MetricCard(
                    label = stringResource(R.string.metric_voltage),
                    value = state.voltageV?.let { "%.1f".format(Locale.US, it) } ?: dash,
                    unit = volt,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = stringResource(R.string.metric_current),
                    value = state.currentA?.let { "%.1f".format(Locale.US, it) } ?: dash,
                    unit = ampere,
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
                    label = stringResource(R.string.metric_phase_current),
                    value = state.phaseCurrentA?.let { "%.1f".format(Locale.US, it) } ?: dash,
                    unit = ampere,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = stringResource(R.string.metric_power),
                    value = state.powerW?.let { "%.0f".format(Locale.US, it) } ?: dash,
                    unit = watt,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = stringResource(R.string.metric_battery),
                    value = state.batteryPercent?.let { "${it.roundToInt()}" } ?: dash,
                    unit = percent,
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
                    label = stringResource(R.string.metric_battery_voltage),
                    value = state.batteryVoltageV?.let { "%.1f".format(Locale.US, it) } ?: dash,
                    unit = volt,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        SectionHeader(stringResource(R.string.section_thermals), accent = RideFluxColors.Danger)
        MetricRow(
            left = {
                MetricCard(
                    label = stringResource(R.string.metric_mos),
                    value = state.mosTemperatureC?.let { "%.0f".format(Locale.US, it) } ?: dash,
                    unit = celsius,
                    valueColor = stoplight(state.mosTemperatureC, warn = 60f, danger = 75f),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = stringResource(R.string.metric_motor),
                    value = state.motorTemperatureC?.let { "%.0f".format(Locale.US, it) } ?: dash,
                    unit = celsius,
                    valueColor = stoplight(state.motorTemperatureC, warn = 70f, danger = 90f),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = stringResource(R.string.metric_battery_temp),
                    value = state.batteryTemperatureC?.let { "%.0f".format(Locale.US, it) } ?: dash,
                    unit = celsius,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = stringResource(R.string.metric_board),
                    value = state.boardTemperatureC?.let { "%.0f".format(Locale.US, it) } ?: dash,
                    unit = celsius,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        SectionHeader(stringResource(R.string.section_distance), accent = RideFluxColors.Neon)
        MetricRow(
            left = {
                MetricCard(
                    label = stringResource(R.string.metric_trip),
                    value = state.displayDistance(state.tripDistanceMetres)
                        ?.let { "%.2f".format(Locale.US, it) } ?: dash,
                    unit = state.distanceUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = stringResource(R.string.metric_total),
                    value = state.displayDistance(state.totalDistanceMetres)
                        ?.let { "%.1f".format(Locale.US, it) } ?: dash,
                    unit = state.distanceUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = stringResource(R.string.metric_ride_time),
                    value = formatDuration(state.rideTimeSeconds),
                    unit = stringResource(R.string.unit_hms),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = stringResource(R.string.metric_mode),
                    value = state.rideMode?.label ?: dash,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        SectionHeader(stringResource(R.string.section_device), accent = RideFluxColors.Cyan)
        MetricRow(
            left = {
                MetricCard(
                    label = stringResource(R.string.metric_model),
                    value = state.identity?.modelName ?: dash,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = stringResource(R.string.metric_family),
                    value = state.identity?.family?.name ?: dash,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = stringResource(R.string.metric_firmware),
                    value = state.identity?.firmwareVersion ?: dash,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = stringResource(R.string.metric_address),
                    value = state.identity?.address ?: dash,
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
