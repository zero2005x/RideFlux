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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rideflux.app.R
import com.rideflux.app.bridge.BridgeService
import com.rideflux.app.bridge.BridgeState
import com.rideflux.app.bridge.GlassesLinkMode
import com.rideflux.app.bridge.GlassesLinkState
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.wheel.WheelFamily
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stateful entry point wired to Hilt. The route composable in the
 * nav graph should call this and pass `onDeviceSelected` to navigate
 * to the dashboard.
 */
@Composable
fun ScannerRoute(
    onDeviceSelected: (address: String, family: WheelFamily?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTripHistory: () -> Unit,
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val bridgeState by BridgeService.state.collectAsStateWithLifecycle()
    val bridgeTarget by BridgeService.activeMac.collectAsStateWithLifecycle()
    val linkMode by BridgeService.linkMode.collectAsStateWithLifecycle()
    val linkState by BridgeService.linkState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ScannerScreen(
        uiState = uiState,
        bridgeState = bridgeState,
        linkMode = linkMode,
        linkState = linkState,
        onStartScan = {
            scope.launch {
                // A wheel usually accepts only one BLE connection. The
                // foreground bridge may still own that connection after
                // the Activity was closed, which makes the wheel stop
                // advertising. Wait for real GATT teardown before scan.
                if (bridgeTarget != null) {
                    BridgeService.clearTarget(context)
                    withTimeoutOrNull(BRIDGE_RELEASE_TIMEOUT_MILLIS) {
                        BridgeService.state
                            .filter { it == BridgeState.STANDBY || it == BridgeState.STOPPED }
                            .first()
                    }
                }
                viewModel.startScan()
            }
        },
        onStopScan = viewModel::stopScan,
        onToggleBridge = { enabled ->
            if (enabled) BridgeService.startStandby(context) else BridgeService.stop(context)
        },
        onSelectLinkMode = { mode -> BridgeService.setLinkMode(context, mode) },
        onDeviceSelected = { onDeviceSelected(it.address, it.family) },
        onOpenSettings = onOpenSettings,
        onOpenTripHistory = onOpenTripHistory,
    )
}

private const val BRIDGE_RELEASE_TIMEOUT_MILLIS = 5_000L

/**
 * Stateless scanner screen. Separated from [ScannerRoute] so previews
 * and unit tests can drive it with synthetic state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    uiState: ScannerUiState,
    bridgeState: BridgeState = BridgeState.STOPPED,
    linkMode: GlassesLinkMode = GlassesLinkMode.ANDROID_BLE,
    linkState: GlassesLinkState = GlassesLinkState.STOPPED,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onToggleBridge: (Boolean) -> Unit = {},
    onSelectLinkMode: (GlassesLinkMode) -> Unit = {},
    onDeviceSelected: (DiscoveredWheel) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenTripHistory: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scanner_title)) },
                actions = {
                    IconButton(onClick = onOpenTripHistory) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = stringResource(R.string.trip_history_title),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            BridgeControlCard(
                state = bridgeState,
                linkMode = linkMode,
                linkState = linkState,
                onToggle = onToggleBridge,
                onSelectLinkMode = onSelectLinkMode,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                ScannerContent(
                    uiState = uiState,
                    onDeviceSelected = onDeviceSelected,
                    contentPadding = PaddingValues(),
                )
            }
        }
    }
}

@Composable
private fun BridgeControlCard(
    state: BridgeState,
    linkMode: GlassesLinkMode,
    linkState: GlassesLinkState,
    onToggle: (Boolean) -> Unit,
    onSelectLinkMode: (GlassesLinkMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state != BridgeState.STOPPED
    val status = stringResource(
        when (state) {
            BridgeState.STOPPED -> R.string.bridge_state_off
            BridgeState.STANDBY -> R.string.bridge_state_standby
            BridgeState.ATTACHING -> R.string.bridge_state_attaching
            BridgeState.RELAYING -> R.string.bridge_state_relaying
            BridgeState.DEGRADED -> R.string.bridge_state_degraded
        },
    )
    val transportStatus = stringResource(
        when (linkState) {
            GlassesLinkState.STOPPED -> R.string.glasses_link_stopped
            GlassesLinkState.STARTING -> R.string.glasses_link_starting
            GlassesLinkState.READY -> R.string.glasses_link_ready
            GlassesLinkState.CONNECTED -> R.string.glasses_link_connected
            GlassesLinkState.ERROR -> R.string.glasses_link_error
        },
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (enabled) Icons.Filled.CastConnected else Icons.Filled.Cast,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.bridge_card_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.bridge_status_combined, status, transportStatus),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                )
            }
            Text(
                text = stringResource(R.string.glasses_connection),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = linkMode == GlassesLinkMode.ANDROID_BLE,
                    onClick = { onSelectLinkMode(GlassesLinkMode.ANDROID_BLE) },
                    label = { Text(stringResource(R.string.link_mode_android_ble)) },
                )
                FilterChip(
                    selected = linkMode == GlassesLinkMode.ROKID_CXR,
                    onClick = { onSelectLinkMode(GlassesLinkMode.ROKID_CXR) },
                    label = { Text(stringResource(R.string.link_mode_rokid_cxr)) },
                )
            }
        }
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
        text = {
            Text(
                stringResource(if (isScanning) R.string.action_stop else R.string.action_scan),
            )
        },
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
        // Non-empty devices take precedence so a stale error (e.g. from
        // a failed flow AFTER devices were already discovered) doesn't
        // replace a useful list with a failure screen.
        devices.isNotEmpty() ->
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
                uiState.errorMessage?.let { message ->
                    item(key = "scan_error") {
                        Text(
                            text = stringResource(R.string.scanner_scan_failed_reason, message),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(devices.distinctBy { it.address }, key = { it.address }) { device ->
                    DeviceCard(device = device, onClick = { onDeviceSelected(device) })
                }
            }

        uiState.errorMessage != null ->
            CenteredMessage(
                title = stringResource(R.string.scanner_scan_failed),
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
                        stringResource(R.string.scanner_searching),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

        devices.isEmpty() ->
            CenteredMessage(
                title = stringResource(R.string.scanner_no_devices),
                subtitle = stringResource(R.string.scanner_no_devices_hint),
                padding = contentPadding,
                illustrationRes = R.drawable.illustration_no_devices,
            )
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
        Row(
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
                    text = device.displayName ?: stringResource(R.string.scanner_unknown_device),
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
    // Read both strings unconditionally: stringResource is a composable
    // call and must not sit behind a data-dependent branch.
    val familyLabel = stringResource(R.string.scanner_device_family, device.family?.name.orEmpty())
    val rssiLabel = stringResource(R.string.unit_dbm, device.rssi ?: 0)
    val parts = buildList {
        device.family?.let { add(familyLabel) }
        device.rssi?.let { add(rssiLabel) }
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
