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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsRoute(
    onNavigateUp: () -> Unit,
    onOpenTripHistory: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pairingCode by viewModel.pairingCode.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = settings,
        pairingCode = pairingCode,
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
    pairingCode: String? = null,
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
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("Alerts")
            ToggleItem("Enable threshold alerts", "Evaluate phone-side safety limits", thresholds.enabled, onAlertsEnabled)
            SliderItem("Speed limit", thresholds.speedLimitKmh, 5f..100f, "km/h", onSpeedLimit)
            SliderItem("MOS temperature", thresholds.temperatureLimitC, 40f..120f, "°C", onTemperatureLimit)
            SliderItem("Low battery", thresholds.lowBatteryPercent, 5f..50f, "%", onLowBattery)
            SliderItem("PWM load", thresholds.pwmAlertPercent, 50f..100f, "%", onPwmAlert)
            HorizontalDivider()
            SectionTitle("Display")
            ToggleItem("Metric units", "Disable for mph and miles", settings.useMetric, onUseMetric)
            ToggleItem("Keep dashboard awake", "Prevent screen sleep while riding", settings.keepScreenOnDashboard, onKeepScreenOn)
            HorizontalDivider()
            SectionTitle("HUD Bridge")
            ToggleItem("Start bridge automatically", "Enter standby when RideFlux starts or the phone boots", settings.bridgeAutostart, onBridgeAutostart)
            ToggleItem("Low-latency standby", "Uses more battery while waiting for a wheel", settings.bridgeStandbyAdvertiseLowLatency, onStandbyLowLatency)
            ListItem(
                headlineContent = { Text("This phone's pairing code") },
                supportingContent = {
                    Text(
                        pairingCode?.let { "$it\nSelect this code on the glasses to pair" }
                            ?: "Preparing…",
                    )
                },
            )
            ListItem(
                headlineContent = { Text("Paired glasses") },
                supportingContent = {
                    Text(settings.hudPeerMac ?: "No glasses have paired with this phone yet")
                },
            )
            HorizontalDivider()
            SectionTitle("About")
            ListItem(
                headlineContent = { Text("Trip history") },
                supportingContent = { Text("Browse and export locally saved rides") },
                leadingContent = { Icon(Icons.Filled.History, contentDescription = null) },
                trailingContent = { TextButton(onClick = onOpenTripHistory) { Text("Open") } },
            )
            ListItem(
                headlineContent = { Text("RideFlux") },
                supportingContent = { Text("Offline-first wheel telemetry and HUD bridge") },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(Locale.US),
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
        trailingContent = { Text("${value.roundToInt()} $unit") },
    )
}
