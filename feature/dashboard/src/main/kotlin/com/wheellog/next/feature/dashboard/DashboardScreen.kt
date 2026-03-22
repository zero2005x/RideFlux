package com.wheellog.next.feature.dashboard

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.wheellog.next.core.common.ext.celsiusToFahrenheit
import com.wheellog.next.core.common.ext.kmhToMph
import com.wheellog.next.core.common.ext.kmToMiles
import com.wheellog.next.domain.model.AlertFlag
import com.wheellog.next.domain.model.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToScan: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                DashboardEffect.NavigateToScan -> onNavigateToScan()
                DashboardEffect.NavigateToSettings -> onNavigateToSettings()
                is DashboardEffect.ShowAlert -> { /* handle vibration/sound in future */ }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WheelLog Next") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = { viewModel.onIntent(DashboardIntent.NavigateToScan) }) {
                        Icon(Icons.Default.Bluetooth, contentDescription = "BLE Scan")
                    }
                    IconButton(onClick = { viewModel.onIntent(DashboardIntent.NavigateToSettings) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ConnectionBadge(uiState.connectionState)

            Spacer(modifier = Modifier.height(24.dp))

            // Speed — large primary gauge
            SpeedDisplay(
                speedKmh = uiState.speedKmh,
                useMetric = uiState.useMetricUnits,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Trip recording button
            RecordButton(
                isRecording = uiState.isRecordingTrip,
                onClick = { viewModel.onIntent(DashboardIntent.ToggleTripRecording) },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Alert bar
            if (uiState.alertFlags.isNotEmpty()) {
                AlertBar(alerts = uiState.alertFlags)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Telemetry grid
            TelemetryGrid(
                batteryPercent = uiState.batteryPercent,
                voltageV = uiState.voltageV,
                temperatureC = uiState.temperatureC,
                currentA = uiState.currentA,
                tripDistanceKm = uiState.tripDistanceKm,
                totalDistanceKm = uiState.totalDistanceKm,
                useMetric = uiState.useMetricUnits,
            )
        }
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isRecording) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
        ),
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
            contentDescription = if (isRecording) "Stop recording" else "Start recording",
        )
    }
}

@Composable
private fun ConnectionBadge(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Text(
        text = state.name,
        color = color,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun SpeedDisplay(speedKmh: Float, useMetric: Boolean) {
    val displaySpeed = if (useMetric) speedKmh else speedKmh.kmhToMph()
    val unit = if (useMetric) "km/h" else "mph"

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "%.1f".format(kotlin.math.abs(displaySpeed)),
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun AlertBar(alerts: Set<AlertFlag>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        alerts.forEach { flag ->
            Text(
                text = flag.name.replace('_', ' '),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun TelemetryGrid(
    batteryPercent: Int,
    voltageV: Float,
    temperatureC: Float,
    currentA: Float,
    tripDistanceKm: Float,
    totalDistanceKm: Float,
    useMetric: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TelemetryCell("Battery", "$batteryPercent%")
            TelemetryCell("Voltage", "%.1fV".format(voltageV))
            TelemetryCell(
                "Temp",
                if (useMetric) "%.0f°C".format(temperatureC)
                else "%.0f°F".format(temperatureC.celsiusToFahrenheit()),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TelemetryCell("Current", "%.1fA".format(currentA))
            TelemetryCell(
                "Trip",
                if (useMetric) "%.2f km".format(tripDistanceKm)
                else "%.2f mi".format(tripDistanceKm.kmToMiles()),
            )
            TelemetryCell(
                "Total",
                if (useMetric) "%.0f km".format(totalDistanceKm)
                else "%.0f mi".format(totalDistanceKm.kmToMiles()),
            )
        }
    }
}

@Composable
private fun TelemetryCell(label: String, value: String) {
    Box(contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}
