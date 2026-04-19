/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.scanner

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rideflux.app.R
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.wheel.WheelFamily

/**
 * Stateful entry point wired to Hilt. The route composable in the
 * nav graph should call this and pass `onDeviceSelected` to navigate
 * to the dashboard.
 */
@Composable
fun ScannerRoute(
    onDeviceSelected: (address: String, family: WheelFamily?) -> Unit,
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ScannerScreen(
        uiState = uiState,
        onStartScan = viewModel::startScan,
        onStopScan = viewModel::stopScan,
        onDeviceSelected = { onDeviceSelected(it.address, it.family) },
    )
}

/**
 * Stateless scanner screen. Separated from [ScannerRoute] so previews
 * and unit tests can drive it with synthetic state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    uiState: ScannerUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceSelected: (DiscoveredWheel) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan for wheels") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        floatingActionButton = {
            ScanToggleFab(
                isScanning = uiState.isScanning,
                onStart = onStartScan,
                onStop = onStopScan,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        ScannerContent(
            uiState = uiState,
            onDeviceSelected = onDeviceSelected,
            contentPadding = innerPadding,
        )
    }
}

@Composable
private fun ScanToggleFab(
    isScanning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    ExtendedFloatingActionButton(
        onClick = { if (isScanning) onStop() else onStart() },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        icon = {
            Icon(
                imageVector = if (isScanning) Icons.Filled.Stop else Icons.AutoMirrored.Filled.BluetoothSearching,
                contentDescription = null,
            )
        },
        text = { Text(if (isScanning) "Stop" else "Scan") },
    )
}

@Composable
private fun ScannerContent(
    uiState: ScannerUiState,
    onDeviceSelected: (DiscoveredWheel) -> Unit,
    contentPadding: PaddingValues,
) {
    val devices = uiState.devices
    when {
        uiState.errorMessage != null ->
            CenteredMessage(
                title = "Scan failed",
                subtitle = uiState.errorMessage,
                padding = contentPadding,
            )

        devices.isEmpty() && uiState.isScanning ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.illustration_scanning),
                        contentDescription = null,
                        modifier = Modifier.size(160.dp),
                    )
                    Spacer(Modifier.size(16.dp))
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(16.dp))
                    Text(
                        "Searching for wheels…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

        devices.isEmpty() ->
            CenteredMessage(
                title = "No devices yet",
                subtitle = "Tap Scan to search for nearby wheels.",
                padding = contentPadding,
                illustrationRes = R.drawable.illustration_no_devices,
            )

        else ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(devices, key = { it.address }) { device ->
                    DeviceCard(device = device, onClick = { onDeviceSelected(device) })
                }
            }
    }
}

@Composable
private fun DeviceCard(device: DiscoveredWheel, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.size(16.dp))
            Column(modifier = Modifier.fillMaxWidth(0.85f)) {
                Text(
                    text = device.displayName ?: "Unknown device",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FamilyAndRssiRow(device)
            }
        }
    }
}

@Composable
private fun FamilyAndRssiRow(device: DiscoveredWheel) {
    val parts = buildList {
        device.family?.let { add("Family: ${it.name}") }
        device.rssi?.let { add("${it} dBm") }
    }
    if (parts.isNotEmpty()) {
        Text(
            text = parts.joinToString("  ·  "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    subtitle: String?,
    padding: PaddingValues,
    illustrationRes: Int? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (illustrationRes != null) {
                Image(
                    painter = painterResource(illustrationRes),
                    contentDescription = null,
                    modifier = Modifier.size(160.dp),
                )
                Spacer(Modifier.size(16.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Spacer(Modifier.size(8.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
