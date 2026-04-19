/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.telemetry.WheelAlert
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Stateful entry point wired to Hilt. The nav graph should invoke
 * this and hand a back callback in from its own scope.
 */
@Composable
fun DashboardRoute(
    onNavigateUp: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeAlert by rememberActiveAlert(viewModel.alerts)

    DashboardScreen(
        uiState = uiState,
        activeAlert = activeAlert,
        onNavigateUp = onNavigateUp,
    )
}

/**
 * Keeps the latest [WheelAlert] surfaced for [ALERT_TTL_MILLIS] after
 * it fires, then clears it so the banner auto-dismisses. Re-emits
 * when a new alert arrives, resetting the timer.
 */
@Composable
private fun rememberActiveAlert(alertsFlow: kotlinx.coroutines.flow.SharedFlow<WheelAlert>): androidx.compose.runtime.State<WheelAlert?> {
    val state = remember { mutableStateOf<WheelAlert?>(null) }
    LaunchedEffect(alertsFlow) {
        alertsFlow.collectLatest { alert ->
            state.value = alert
            kotlinx.coroutines.delay(ALERT_TTL_MILLIS)
            if (state.value === alert) state.value = null
        }
    }
    return state
}

private const val ALERT_TTL_MILLIS: Long = 6_000L

/**
 * Stateless dashboard screen. Decoupled from the ViewModel so
 * previews and instrumentation tests can drive it directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    activeAlert: WheelAlert?,
    onNavigateUp: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.identity?.modelName
                            ?: uiState.identity?.address
                            ?: "Dashboard",
                        maxLines = 1,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AlertBanner(alert = activeAlert)
            ConnectionStatusRow(state = uiState.connectionState)
            SpeedometerDisplay(speedKmh = uiState.speedKmh)
            BatteryGauge(
                percent = uiState.batteryPercent,
                voltageV = uiState.voltageV,
            )
            SecondaryStatsRow(uiState)
        }
    }
}

// ---------- Modular composables ----------------------------------------

/**
 * Large, HUD-friendly digital speedometer. Fills the widest part of
 * the screen with a single bold value; units and peripheral info
 * stay muted.
 */
@Composable
fun SpeedometerDisplay(speedKmh: Float?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val display = speedKmh?.let { "%.0f".format(it) } ?: "--"
            Text(
                text = display,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "km / h",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Combines a circular percentage dial and a linear track with the
 * voltage readout alongside. Tint shifts to the error colour when
 * the battery drops below [LOW_BATTERY_THRESHOLD].
 */
@Composable
fun BatteryGauge(
    percent: Float?,
    voltageV: Float?,
    modifier: Modifier = Modifier,
) {
    val clamped = percent?.coerceIn(0f, 100f)
    val isLow = clamped != null && clamped <= LOW_BATTERY_THRESHOLD
    val tint = if (isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(88.dp)) {
                CircularProgressIndicator(
                    progress = { (clamped ?: 0f) / 100f },
                    modifier = Modifier.fillMaxSize(),
                    color = tint,
                    strokeWidth = 8.dp,
                    trackColor = MaterialTheme.colorScheme.outline,
                    strokeCap = ProgressIndicatorDefaults.CircularDeterminateStrokeCap,
                )
                Text(
                    text = clamped?.let { "${it.roundToInt()}%" } ?: "--%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(20.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.BatteryFull,
                        contentDescription = null,
                        tint = tint,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Battery",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (clamped ?: 0f) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = tint,
                    trackColor = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = voltageV?.let { "%.1f V".format(it) } ?: "-- V",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private const val LOW_BATTERY_THRESHOLD: Float = 20f

/**
 * High-visibility banner that appears only when [alert] is non-null.
 * Uses the error container palette for TiltBack / SpeedCutoff and a
 * softer warning tone for LowBattery / OverTemperature so the rider
 * can distinguish severity at a glance.
 */
@Composable
fun AlertBanner(alert: WheelAlert?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = alert != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val a = alert ?: return@AnimatedVisibility
        val (title, body, severe) = describeAlert(a)
        val container = if (severe) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
        }
        val content = if (severe) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = container,
            contentColor = content,
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private data class AlertDescription(val title: String, val body: String, val severe: Boolean)

private fun describeAlert(alert: WheelAlert): AlertDescription = when (alert) {
    is WheelAlert.TiltBack -> AlertDescription(
        title = "TILT-BACK",
        body = "Speed ${"%.0f".format(alert.speedKmh)} km/h · limit ${"%.0f".format(alert.limit)} km/h",
        severe = true,
    )
    is WheelAlert.SpeedCutoff -> AlertDescription(
        title = "SPEED CUTOFF",
        body = "Motor cut at ${"%.0f".format(alert.speedKmh)} km/h",
        severe = true,
    )
    is WheelAlert.LowBattery -> AlertDescription(
        title = "Low battery",
        body = "Pack voltage ${"%.1f".format(alert.voltageV)} V",
        severe = false,
    )
    is WheelAlert.OverTemperature -> AlertDescription(
        title = "Over temperature",
        body = "${alert.source.name}" + (alert.temperatureC?.let { " · ${"%.0f".format(it)}°C" } ?: ""),
        severe = true,
    )
    is WheelAlert.FallDown -> AlertDescription(
        title = "FALL DETECTED",
        body = "Wheel reports a fall event",
        severe = true,
    )
    is WheelAlert.FaultSetChanged -> AlertDescription(
        title = "Fault set changed",
        body = buildString {
            if (alert.added.isNotEmpty()) append("+${alert.added.size} faults ")
            if (alert.removed.isNotEmpty()) append("-${alert.removed.size} cleared")
        }.ifBlank { "Updated" },
        severe = alert.added.isNotEmpty(),
    )
    is WheelAlert.Raw -> AlertDescription(
        title = "${alert.domain} alert 0x${alert.code.toString(16).uppercase()}",
        body = "${alert.payload.size} bytes",
        severe = false,
    )
}

/**
 * Shows the current transport / handshake phase so the rider sees
 * when the wheel is actually live vs. still negotiating.
 */
@Composable
private fun ConnectionStatusRow(state: ConnectionState) {
    val (label, colour) = when (state) {
        ConnectionState.Disconnected -> "Disconnected" to MaterialTheme.colorScheme.error
        ConnectionState.Connecting -> "Connecting…" to MaterialTheme.colorScheme.tertiary
        is ConnectionState.Handshaking -> "Handshaking (${state.family.name})" to MaterialTheme.colorScheme.tertiary
        ConnectionState.Ready -> "Ready" to MaterialTheme.colorScheme.primary
        is ConnectionState.Failed -> "Failed: ${state.reason.name}" to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color = colour, shape = RoundedCornerShape(5.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SecondaryStatsRow(uiState: DashboardUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatTile(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Bolt,
            label = "Current",
            value = uiState.currentA?.let { "%.1f A".format(it) } ?: "-- A",
        )
        StatTile(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Thermostat,
            label = "MOS",
            value = uiState.mosTemperatureC?.let { "%.0f°C".format(it) } ?: "--°C",
        )
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
