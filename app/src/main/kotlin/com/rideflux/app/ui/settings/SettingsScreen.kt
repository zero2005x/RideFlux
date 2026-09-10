/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rideflux.app.R
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsRoute(
    onNavigateUp: () -> Unit,
    onOpenTripHistory: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = settings,
        onNavigateUp = onNavigateUp,
        onOpenTripHistory = onOpenTripHistory,
        onSpeedLimit = viewModel::setSpeedLimit,
        onTemperatureLimit = viewModel::setTemperatureLimit,
        onLowBattery = viewModel::setLowBattery,
        onPwmAlert = viewModel::setPwmAlert,
        onAlertsEnabled = viewModel::setAlertsEnabled,
        onUseMetric = viewModel::setUseMetric,
        onKeepScreenOn = viewModel::setKeepScreenOn,
        onBridgeAutostart = viewModel::setBridgeAutostart,
        onStandbyLowLatency = viewModel::setStandbyLowLatency,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: com.rideflux.domain.settings.AppSettings,
    onNavigateUp: () -> Unit,
    onOpenTripHistory: () -> Unit,
    onSpeedLimit: (Float) -> Unit,
    onTemperatureLimit: (Float) -> Unit,
    onLowBattery: (Float) -> Unit,
    onPwmAlert: (Float) -> Unit,
    onAlertsEnabled: (Boolean) -> Unit,
    onUseMetric: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onBridgeAutostart: (Boolean) -> Unit,
    onStandbyLowLatency: (Boolean) -> Unit,
) {
    val thresholds = settings.alertThresholds
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SectionTitle(stringResource(R.string.settings_section_alerts))
            ToggleItem(
                stringResource(R.string.settings_enable_alerts_title),
                stringResource(R.string.settings_enable_alerts_subtitle),
                thresholds.enabled,
                onAlertsEnabled,
            )
            SliderItem(
                stringResource(R.string.settings_speed_limit),
                thresholds.speedLimitKmh,
                5f..100f,
                stringResource(R.string.unit_kmh),
                onSpeedLimit,
            )
            SliderItem(
                stringResource(R.string.settings_mos_temperature),
                thresholds.temperatureLimitC,
                40f..120f,
                stringResource(R.string.unit_celsius),
                onTemperatureLimit,
            )
            SliderItem(
                stringResource(R.string.settings_low_battery),
                thresholds.lowBatteryPercent,
                5f..50f,
                stringResource(R.string.unit_percent),
                onLowBattery,
            )
            SliderItem(
                stringResource(R.string.settings_pwm_load),
                thresholds.pwmAlertPercent,
                50f..100f,
                stringResource(R.string.unit_percent),
                onPwmAlert,
            )
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_display))
            ToggleItem(
                stringResource(R.string.settings_metric_units_title),
                stringResource(R.string.settings_metric_units_subtitle),
                settings.useMetric,
                onUseMetric,
            )
            ToggleItem(
                stringResource(R.string.settings_keep_awake_title),
                stringResource(R.string.settings_keep_awake_subtitle),
                settings.keepScreenOnDashboard,
                onKeepScreenOn,
            )
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_hud_bridge))
            ToggleItem(
                stringResource(R.string.settings_bridge_autostart_title),
                stringResource(R.string.settings_bridge_autostart_subtitle),
                settings.bridgeAutostart,
                onBridgeAutostart,
            )
            ToggleItem(
                stringResource(R.string.settings_low_latency_title),
                stringResource(R.string.settings_low_latency_subtitle),
                settings.bridgeStandbyAdvertiseLowLatency,
                onStandbyLowLatency,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_paired_mac)) },
                supportingContent = {
                    Text(settings.hudPeerMac ?: stringResource(R.string.settings_paired_mac_unset))
                },
            )
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.trip_history_title)) },
                supportingContent = { Text(stringResource(R.string.trip_history_subtitle)) },
                leadingContent = { Icon(Icons.Filled.History, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = onOpenTripHistory) {
                        Text(stringResource(R.string.action_open))
                    }
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = { Text(stringResource(R.string.settings_about_subtitle)) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        // Locale.getDefault(), not Locale.US: the section titles are now
        // translated, and casing rules are language-specific.
        text = text.uppercase(Locale.getDefault()),
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun ToggleItem(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = value, onCheckedChange = onChange) },
    )
}

@Composable
private fun SliderItem(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onChange: (Float) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = { onChange(it.roundToInt().toFloat()) },
                valueRange = range,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        trailingContent = {
            Text(stringResource(R.string.slider_value, value.roundToInt(), unit))
        },
    )
}
