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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rideflux.app.R
import com.rideflux.app.bridge.BridgeService
import com.rideflux.app.bridge.BridgeState
import com.rideflux.app.bridge.GlassesAuthorizationRequest
import com.rideflux.app.bridge.GlassesLinkMode
import com.rideflux.app.bridge.GlassesLinkState
import com.rideflux.app.ui.permission.openNotificationSettings
import com.rideflux.app.ui.permission.rememberNotificationPermissionPrompt
import com.rideflux.app.ui.permission.rememberNotificationsEnabled
import com.rideflux.app.ui.permission.shouldShowNotificationsHint
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.garage.VehicleCalibration
import com.rideflux.domain.garage.VehicleProfile
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.settings.AlertThresholds
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
    onDeviceSelected: (address: String, family: WheelFamily?, category: PlevCategory) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTripHistory: () -> Unit,
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val bridgeState by BridgeService.state.collectAsStateWithLifecycle()
    val bridgeTarget by BridgeService.activeMac.collectAsStateWithLifecycle()
    val linkMode by BridgeService.linkMode.collectAsStateWithLifecycle()
    val linkState by BridgeService.linkState.collectAsStateWithLifecycle()
    val pendingAuth by BridgeService.pendingAuthorization.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val promptForNotifications = rememberNotificationPermissionPrompt()
    val notificationsEnabled = rememberNotificationsEnabled()
    ScannerScreen(
        uiState = uiState,
        bridgeState = bridgeState,
        linkMode = linkMode,
        linkState = linkState,
        pendingAuth = pendingAuth,
        notificationsHint = shouldShowNotificationsHint(
            bridgeActive = bridgeState != BridgeState.STOPPED,
            notificationsEnabled = notificationsEnabled,
        ),
        onOpenNotificationSettings = { openNotificationSettings(context) },
        onApproveAuth = { BridgeService.approveGlasses(context, it) },
        onRejectAuth = { BridgeService.rejectGlasses(context, it) },
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
            if (enabled) {
                // Notifications carry the pairing-approval request; ask before the bridge starts,
                // but start it whatever the answer is.
                promptForNotifications { BridgeService.startStandby(context) }
            } else {
                BridgeService.stop(context)
            }
        },
        onSelectLinkMode = { mode -> BridgeService.setLinkMode(context, mode) },
        // The selection event, not this route, decides whether a family is a
        // manual claim: Scanned keeps `family = null` (the discovered GATT
        // table decides) while Brand forwards the rider's choice. See
        // [ScannerSelection] and its regression.
        onDeviceSelected = { selection ->
            viewModel.recordSelection(selection)
            scannerDeviceNavigator(onDeviceSelected)(selection)
        },
        onScooterSelected = {
            viewModel.recordScooterSelection(it)
            onDeviceSelected(it.address, null, PlevCategory.SCOOTER)
        },
        onSavedVehicleSelected = { profile ->
            onDeviceSelected(profile.address, profile.family, profile.category)
        },
        onRemoveSavedVehicle = viewModel::removeVehicle,
        onRenameSavedVehicle = viewModel::renameVehicle,
        onSetSavedVehicleFamily = viewModel::setVehicleFamily,
        onSaveSavedVehicle = viewModel::saveVehicle,
        onResetSavedVehicle = viewModel::resetVehicleOverrides,
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
    pendingAuth: GlassesAuthorizationRequest? = null,
    notificationsHint: Boolean = false,
    onOpenNotificationSettings: () -> Unit = {},
    onApproveAuth: (GlassesAuthorizationRequest) -> Unit = {},
    onRejectAuth: (GlassesAuthorizationRequest) -> Unit = {},
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onToggleBridge: (Boolean) -> Unit = {},
    onSelectLinkMode: (GlassesLinkMode) -> Unit = {},
    onDeviceSelected: (ScannerSelection) -> Unit,
    onScooterSelected: (ScooterDevice) -> Unit = {},
    onSavedVehicleSelected: (VehicleProfile) -> Unit = {},
    onRemoveSavedVehicle: (String) -> Unit = {},
    onRenameSavedVehicle: (String, String) -> Unit = { _, _ -> },
    onSetSavedVehicleFamily: (String, WheelFamily?) -> Unit = { _, _ -> },
    onSaveSavedVehicle: ((String, String, WheelFamily?, AlertThresholds?, VehicleCalibration) -> Unit)? = null,
    onResetSavedVehicle: ((String) -> Unit)? = null,
    onOpenSettings: () -> Unit = {},
    onOpenTripHistory: () -> Unit = {},
) {
    var manualFamilySelectionDevice by remember { mutableStateOf<DiscoveredWheel?>(null) }

    manualFamilySelectionDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { manualFamilySelectionDevice = null },
            title = { Text(stringResource(R.string.scanner_select_brand_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // One entry per brand: every button is an explicit rider
                    // choice and therefore emits ScannerSelection.Brand. VESC
                    // is read-only (telemetry + Ready), but it is still a
                    // manual family and must reach the repository as one.
                    ScannerBrand.entries.forEach { brand ->
                        TextButton(
                            onClick = {
                                manualFamilySelectionDevice = null
                                onDeviceSelected(brand.select(device))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(brand.labelRes))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { manualFamilySelectionDevice = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

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
            pendingAuth?.let { req ->
                GlassesAuthBanner(
                    request = req,
                    onAllow = onApproveAuth,
                    onDeny = onRejectAuth,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            BridgeControlCard(
                state = bridgeState,
                linkMode = linkMode,
                linkState = linkState,
                onToggle = onToggleBridge,
                onSelectLinkMode = onSelectLinkMode,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SavedVehiclesCard(
                profiles = uiState.savedVehicles,
                onSelect = onSavedVehicleSelected,
                onRemove = { onRemoveSavedVehicle(it.address) },
                onRename = onRenameSavedVehicle,
                onSetFamily = onSetSavedVehicleFamily,
                onSave = onSaveSavedVehicle,
                onReset = onResetSavedVehicle,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (notificationsHint) {
                NotificationsOffHint(
                    onOpenSettings = onOpenNotificationSettings,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                ScannerContent(
                    uiState = uiState,
                    onDeviceSelected = onDeviceSelected,
                    onScooterSelected = onScooterSelected,
                    onUnrecognisedDeviceSelected = { manualFamilySelectionDevice = it },
                    onChangeBrand = { manualFamilySelectionDevice = it },
                    contentPadding = PaddingValues(),
                )
            }
        }
    }
}

@Composable
private fun GlassesAuthBanner(
    request: GlassesAuthorizationRequest,
    onAllow: (GlassesAuthorizationRequest) -> Unit,
    onDeny: (GlassesAuthorizationRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.glasses_auth_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.size(8.dp))
            val message = if (request.isLegacy) {
                stringResource(R.string.glasses_auth_dialog_legacy_message, request.shortCode)
            } else {
                stringResource(R.string.glasses_auth_dialog_message, request.shortCode)
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.size(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { onDeny(request) }) {
                    Text(stringResource(R.string.action_deny))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = { onAllow(request) }) {
                    Text(stringResource(R.string.action_allow))
                }
            }
        }
    }
}

/**
 * Says that a pairing request only shows while the app is open, and leads to the system page where
 * notifications can be turned back on (the permission dialog no longer appears once declined).
 */
@Composable
private fun NotificationsOffHint(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.bridge_notifications_off_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.action_open_settings))
                }
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
private fun SavedVehiclesCard(
    profiles: List<VehicleProfile>,
    onSelect: (VehicleProfile) -> Unit,
    onRemove: (VehicleProfile) -> Unit,
    onRename: (String, String) -> Unit,
    onSetFamily: (String, WheelFamily?) -> Unit,
    onSave: ((String, String, WheelFamily?, AlertThresholds?, VehicleCalibration) -> Unit)?,
    onReset: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<VehicleProfile?>(null) }

    editing?.let { profile ->
        var name by remember(profile.id) { mutableStateOf(profile.displayName) }
        var family by remember(profile.id) { mutableStateOf(profile.family) }
        var alertsOverride by remember(profile.id) {
            mutableStateOf(profile.alertThresholds != null)
        }
        val initialThresholds = profile.alertThresholds ?: AlertThresholds()
        var alertsEnabled by remember(profile.id) { mutableStateOf(initialThresholds.enabled) }
        var speedLimit by remember(profile.id) { mutableStateOf(initialThresholds.speedLimitKmh.toString()) }
        var temperatureLimit by remember(profile.id) {
            mutableStateOf(initialThresholds.temperatureLimitC.toString())
        }
        var lowBattery by remember(profile.id) { mutableStateOf(initialThresholds.lowBatteryPercent.toString()) }
        var pwmLoad by remember(profile.id) { mutableStateOf(initialThresholds.pwmAlertPercent.toString()) }
        var pitch by remember(profile.id) {
            mutableStateOf(profile.calibration.pitchOffsetDegrees.toString())
        }
        var roll by remember(profile.id) {
            mutableStateOf(profile.calibration.rollOffsetDegrees.toString())
        }
        val thresholdsValid = !alertsOverride || listOf(
            speedLimit.toFloatOrNull()?.takeIf { it in 1f..150f },
            temperatureLimit.toFloatOrNull()?.takeIf { it in 20f..150f },
            lowBattery.toFloatOrNull()?.takeIf { it in 1f..99f },
            pwmLoad.toFloatOrNull()?.takeIf { it in 1f..100f },
        ).all { it != null }
        val calibrationValid = pitch.toFloatOrNull()?.let { it in -30f..30f } == true &&
            roll.toFloatOrNull()?.let { it in -30f..30f } == true
        val canSave = name.trim().isNotEmpty() && thresholdsValid && calibrationValid
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.scanner_vehicle_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.scanner_vehicle_name_label)) },
                        singleLine = true,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(
                                selected = family == null,
                                onClick = { family = null },
                                label = { Text("Auto") },
                            )
                        }
                        items(ScannerBrand.entries) { brand ->
                            FilterChip(
                                selected = family == brand.family,
                                onClick = { family = brand.family },
                                label = { Text(stringResource(brand.labelRes), fontSize = 11.sp) },
                            )
                        }
                    }
                    FilterChip(
                        selected = alertsOverride,
                        onClick = { alertsOverride = !alertsOverride },
                        label = { Text(stringResource(R.string.scanner_vehicle_alerts_override)) },
                    )
                    if (alertsOverride) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = speedLimit,
                                onValueChange = { speedLimit = it },
                                label = { Text(stringResource(R.string.settings_speed_limit), fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("vehicle_speed"),
                            )
                            OutlinedTextField(
                                value = temperatureLimit,
                                onValueChange = { temperatureLimit = it },
                                label = { Text(stringResource(R.string.settings_mos_temperature), fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("vehicle_temperature"),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = lowBattery,
                                onValueChange = { lowBattery = it },
                                label = { Text(stringResource(R.string.settings_low_battery), fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("vehicle_low_battery"),
                            )
                            OutlinedTextField(
                                value = pwmLoad,
                                onValueChange = { pwmLoad = it },
                                label = { Text(stringResource(R.string.settings_pwm_load), fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("vehicle_pwm"),
                            )
                        }
                        FilterChip(
                            selected = alertsEnabled,
                            onClick = { alertsEnabled = !alertsEnabled },
                            label = { Text(stringResource(R.string.settings_enable_alerts_title), fontSize = 11.sp) },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = pitch,
                            onValueChange = { pitch = it },
                            label = { Text(stringResource(R.string.scanner_vehicle_pitch), fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("vehicle_pitch"),
                        )
                        OutlinedTextField(
                            value = roll,
                            onValueChange = { roll = it },
                            label = { Text(stringResource(R.string.scanner_vehicle_roll), fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("vehicle_roll"),
                        )
                    }
                    Text(
                        stringResource(R.string.scanner_vehicle_calibration_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canSave,
                    onClick = {
                        val thresholds = if (alertsOverride) AlertThresholds(
                            speedLimitKmh = speedLimit.toFloat(),
                            temperatureLimitC = temperatureLimit.toFloat(),
                            lowBatteryPercent = lowBattery.toFloat(),
                            pwmAlertPercent = pwmLoad.toFloat(),
                            enabled = alertsEnabled,
                        ) else null
                        val calibration = VehicleCalibration(
                            pitchOffsetDegrees = pitch.toFloat(),
                            rollOffsetDegrees = roll.toFloat(),
                        )
                        if (onSave != null) {
                            onSave(profile.address, name, family, thresholds, calibration)
                        } else {
                            onRename(profile.address, name)
                            onSetFamily(profile.address, family)
                        }
                        editing = null
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                Row {
                    if (onReset != null) {
                        TextButton(onClick = {
                            onReset(profile.address)
                            editing = null
                        }) {
                            Text(stringResource(R.string.settings_ring_key_reset))
                        }
                    }
                    TextButton(onClick = {
                        onRemove(profile)
                        editing = null
                    }) {
                        Text(
                            stringResource(R.string.scanner_vehicle_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                stringResource(R.string.scanner_saved_vehicles),
                style = MaterialTheme.typography.titleSmall,
            )
            if (profiles.isEmpty()) {
                Text(
                    stringResource(R.string.scanner_saved_vehicles_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(profiles, key = { it.id }) { profile ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                TextButton(onClick = { onSelect(profile) }) {
                                    Text(profile.displayName)
                                }
                                Text(
                                    text = listOfNotNull(
                                        profile.category.name,
                                        profile.family?.name ?: "AUTO",
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row {
                                    IconButton(onClick = { editing = profile }) {
                                        Icon(
                                            Icons.Filled.Edit,
                                            contentDescription = stringResource(R.string.scanner_vehicle_edit_title),
                                        )
                                    }
                                    IconButton(onClick = { onRemove(profile) }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.scanner_vehicle_delete),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerContent(
    uiState: ScannerUiState,
    onDeviceSelected: (ScannerSelection) -> Unit,
    onScooterSelected: (ScooterDevice) -> Unit,
    onUnrecognisedDeviceSelected: (DiscoveredWheel) -> Unit = {
        onDeviceSelected(ScannerSelection.Scanned(it))
    },
    onChangeBrand: (DiscoveredWheel) -> Unit = onUnrecognisedDeviceSelected,
    contentPadding: PaddingValues,
) {
    val devices = uiState.devices.distinctBy { it.address }
    val scooters = uiState.scooters.distinctBy { it.address }
    val recognised = devices.filter { it.family != null }
    val unrecognised = devices.filter { it.family == null }
    when {
        // Non-empty devices take precedence so a stale error (e.g. from
        // a failed flow AFTER devices were already discovered) doesn't
        // replace a useful list with a failure screen.
        devices.isNotEmpty() || scooters.isNotEmpty() ->
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
                if (recognised.isNotEmpty()) {
                    item(key = "recognised_header") {
                        Text(
                            text = stringResource(R.string.scanner_group_recognised),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(recognised, key = { it.address }) { device ->
                    DeviceCard(
                        device = device,
                        onClick = { onDeviceSelected(ScannerSelection.Scanned(device)) },
                        onChangeBrand = { onChangeBrand(device) },
                    )
                }
                if (scooters.isNotEmpty()) {
                    item(key = "scooter_header") {
                        Text(
                            text = stringResource(R.string.scanner_group_scooters),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(scooters, key = { "scooter_${it.address}" }) { scooter ->
                        ScooterCard(scooter = scooter, onClick = { onScooterSelected(scooter) })
                    }
                }
                if (unrecognised.isNotEmpty()) {
                    item(key = "unrecognised_header") {
                        Text(
                            text = stringResource(R.string.scanner_group_unrecognised),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(unrecognised, key = { it.address }) { device ->
                        DeviceCard(
                            device = device,
                            onClick = { onUnrecognisedDeviceSelected(device) },
                            onChangeBrand = { onUnrecognisedDeviceSelected(device) },
                        )
                    }
                }
            }

        uiState.errorMessage != null ->
            CenteredMessage(
                title = stringResource(R.string.scanner_scan_failed),
                subtitle = uiState.errorMessage,
                padding = contentPadding,
            )

        devices.isEmpty() && scooters.isEmpty() && uiState.isScanning ->
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

        devices.isEmpty() && scooters.isEmpty() ->
            CenteredMessage(
                title = stringResource(R.string.scanner_no_devices),
                subtitle = stringResource(R.string.scanner_no_devices_hint),
                padding = contentPadding,
                illustrationRes = R.drawable.illustration_no_devices,
            )
    }
}

@Composable
private fun ScooterCard(scooter: ScooterDevice, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bluetooth, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(16.dp))
            Column {
                Text(scooter.model, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(scooter.address, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DiscoveredWheel,
    onClick: () -> Unit,
    onChangeBrand: (() -> Unit)? = null,
) {
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
            Column(modifier = Modifier.weight(1f)) {
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
            if (onChangeBrand != null) {
                IconButton(onClick = onChangeBrand) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.scanner_change_brand),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
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
    val weak = isWeakSignal(device.rssi)
    val parts = buildList {
        device.family?.let { add(familyLabel) }
        device.rssi?.let { add(rssiLabel) }
    }
    if (parts.isNotEmpty()) {
        Text(
            text = parts.joinToString("  ·  "),
            style = MaterialTheme.typography.labelLarge,
            // A weak advertiser still renders as a perfectly tappable
            // row, so the number alone says nothing to anyone who does
            // not read dBm. Colouring it is what makes a doomed connect
            // attempt visible before it is made.
            color = if (weak) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
    if (weak) {
        Text(
            text = stringResource(R.string.scanner_signal_weak),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Below this, a GATT connect usually fails or drops during the
 * handshake.
 *
 * -90 dBm is roughly where a BLE link stops being reliable in the
 * open; a wheel is typically behind a metal shell and the rider's own
 * body, so treat anything at or under it as not worth a silent retry
 * loop. An absent RSSI is not treated as weak — no reading is not the
 * same as a bad one.
 */
internal const val WEAK_RSSI_DBM = -90

internal fun isWeakSignal(rssi: Int?): Boolean = rssi != null && rssi <= WEAK_RSSI_DBM

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
