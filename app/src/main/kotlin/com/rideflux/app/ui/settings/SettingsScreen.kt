/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rideflux.app.R
import com.rideflux.app.bridge.ApprovedGlasses
import com.rideflux.app.bridge.BridgeService
import com.rideflux.app.bridge.GlassesLinkMode
import java.util.Locale
import kotlin.math.roundToInt

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun SettingsRoute(
    onNavigateUp: () -> Unit,
    onOpenTripHistory: () -> Unit,
    onOpenGlassesSetup: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pairingCode by viewModel.pairingCode.collectAsStateWithLifecycle()
    val linkMode by BridgeService.linkMode.collectAsStateWithLifecycle()
    val approvedGlasses by viewModel.approvedGlasses.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.backupEvent.collect { event ->
            val message = when (event) {
                is BackupUiEvent.ExportSuccess ->
                    context.getString(R.string.settings_backup_export_success, event.tripCount)
                is BackupUiEvent.ExportError ->
                    context.getString(R.string.settings_backup_export_error, event.message)
                is BackupUiEvent.ImportSuccess ->
                    context.getString(
                        R.string.settings_backup_import_success,
                        event.result.tripsImported,
                        event.result.tripsSkipped,
                    )
                is BackupUiEvent.ImportError ->
                    context.getString(R.string.settings_backup_import_error, event.message)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val isLearningRingKey by viewModel.isLearningRingKey.collectAsStateWithLifecycle()
    val bondedDevices = remember { viewModel.getBondedBluetoothDevices() }

    SettingsScreen(
        settings = settings,
        pairingCode = pairingCode,
        linkMode = linkMode,
        approvedGlasses = approvedGlasses,
        isLearningRingKey = isLearningRingKey,
        bondedDevices = bondedDevices,
        onRemoveApprovedGlasses = viewModel::removeApprovedGlasses,
        onSelectLinkMode = { mode -> BridgeService.setLinkMode(context, mode) },
        onNavigateUp = onNavigateUp,
        onOpenTripHistory = onOpenTripHistory,
        onOpenGlassesSetup = onOpenGlassesSetup,
        onExportBackup = viewModel::exportBackup,
        onImportBackup = viewModel::importBackup,
        onSpeedLimit = viewModel::setSpeedLimit,
        onTemperatureLimit = viewModel::setTemperatureLimit,
        onLowBattery = viewModel::setLowBattery,
        onPwmAlert = viewModel::setPwmAlert,
        onAlertsEnabled = viewModel::setAlertsEnabled,
        onUseMetric = viewModel::setUseMetric,
        onKeepScreenOn = viewModel::setKeepScreenOn,
        onBridgeAutostart = viewModel::setBridgeAutostart,
        onStandbyLowLatency = viewModel::setStandbyLowLatency,
        onToggleMirror = { viewModel.setHudMirrorHorizontally(!settings.hudMirrorHorizontally) },
        onStartLearnRingKey = viewModel::startLearningRingKey,
        onStopLearnRingKey = viewModel::stopLearningRingKey,
        onResetRingKey = viewModel::resetRingKey,
        onSelectPreferredGlasses = viewModel::setPreferredGlassesMac,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: com.rideflux.domain.settings.AppSettings,
    pairingCode: String? = null,
    linkMode: GlassesLinkMode = GlassesLinkMode.ANDROID_BLE,
    approvedGlasses: List<ApprovedGlasses> = emptyList(),
    isLearningRingKey: Boolean = false,
    bondedDevices: List<SettingsViewModel.BondedDevice> = emptyList(),
    onRemoveApprovedGlasses: (ApprovedGlasses) -> Unit = {},
    onSelectLinkMode: (GlassesLinkMode) -> Unit = {},
    onNavigateUp: () -> Unit,
    onOpenTripHistory: () -> Unit,
    onOpenGlassesSetup: () -> Unit = {},
    onExportBackup: (Uri) -> Unit = {},
    onImportBackup: (Uri, Boolean) -> Unit = { _, _ -> },
    onSpeedLimit: (Float) -> Unit,
    onTemperatureLimit: (Float) -> Unit,
    onLowBattery: (Float) -> Unit,
    onPwmAlert: (Float) -> Unit,
    onAlertsEnabled: (Boolean) -> Unit,
    onUseMetric: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onBridgeAutostart: (Boolean) -> Unit,
    onStandbyLowLatency: (Boolean) -> Unit,
    onToggleMirror: () -> Unit = {},
    onStartLearnRingKey: () -> Unit = {},
    onStopLearnRingKey: () -> Unit = {},
    onResetRingKey: () -> Unit = {},
    onSelectPreferredGlasses: (String?) -> Unit = {},
) {
    val thresholds = settings.alertThresholds

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) onExportBackup(uri)
    }

    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pendingImportUri = uri
    }

    if (pendingImportUri != null) {
        val uri = pendingImportUri!!
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(stringResource(R.string.settings_backup_import_dialog_title)) },
            text = { Text(stringResource(R.string.settings_backup_import_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onImportBackup(uri, false)
                        pendingImportUri = null
                    },
                ) {
                    Text(stringResource(R.string.settings_backup_import_mode_merge))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            onImportBackup(uri, true)
                            pendingImportUri = null
                        },
                    ) {
                        Text(stringResource(R.string.settings_backup_import_mode_replace))
                    }
                    TextButton(onClick = { pendingImportUri = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    var showGlassesPicker by remember { mutableStateOf(false) }
    if (showGlassesPicker) {
        AlertDialog(
            onDismissRequest = { showGlassesPicker = false },
            title = { Text(stringResource(R.string.settings_select_glasses_dialog_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_select_glasses_auto)) },
                        modifier = Modifier.clickable {
                            onSelectPreferredGlasses(null)
                            showGlassesPicker = false
                        },
                    )
                    bondedDevices.forEach { device ->
                        ListItem(
                            headlineContent = { Text(device.name) },
                            supportingContent = { Text(device.address) },
                            modifier = Modifier.clickable {
                                onSelectPreferredGlasses(device.address)
                                showGlassesPicker = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGlassesPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (isLearningRingKey) {
        AlertDialog(
            onDismissRequest = onStopLearnRingKey,
            title = { Text(stringResource(R.string.settings_ring_learning_dialog_title)) },
            text = { Text(stringResource(R.string.settings_ring_learning_dialog_message)) },
            confirmButton = {
                TextButton(onClick = onStopLearnRingKey) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
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
            ListItem(
                headlineContent = { Text(stringResource(R.string.glasses_setup_title)) },
                supportingContent = { Text(stringResource(R.string.glasses_setup_subtitle)) },
                trailingContent = {
                    TextButton(onClick = onOpenGlassesSetup) {
                        Text(stringResource(R.string.action_open))
                    }
                },
            )
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
            ToggleItem(
                stringResource(R.string.settings_hud_mirror_title),
                stringResource(R.string.settings_hud_mirror_subtitle),
                settings.hudMirrorHorizontally,
                { onToggleMirror() },
            )
            GlassesLinkModeItem(linkMode = linkMode, onSelectLinkMode = onSelectLinkMode)
            if (linkMode == GlassesLinkMode.ROKID_CXR) {
                val currentGlasses = settings.preferredGlassesMac
                val glassesSummary = if (currentGlasses != null) {
                    val found = bondedDevices.firstOrNull { it.address.equals(currentGlasses, ignoreCase = true) }
                    if (found != null) "${found.name} (${found.address})" else currentGlasses
                } else {
                    stringResource(R.string.settings_select_glasses_auto)
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_select_glasses_title)) },
                    supportingContent = { Text(glassesSummary) },
                    trailingContent = {
                        TextButton(onClick = { showGlassesPicker = true }) {
                            Text(stringResource(R.string.action_select))
                        }
                    },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_pairing_code_title)) },
                supportingContent = {
                    // Both branches resolved unconditionally: stringResource
                    // is a composable call and must not sit behind a
                    // data-dependent branch.
                    val preparing = stringResource(R.string.settings_pairing_code_preparing)
                    val hint = stringResource(
                        R.string.settings_pairing_code_hint,
                        pairingCode.orEmpty(),
                    )
                    Text(if (pairingCode != null) hint else preparing)
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_paired_mac)) },
                supportingContent = {
                    Text(settings.hudPeerMac ?: stringResource(R.string.settings_paired_mac_unset))
                },
            )
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_ring_key_title))
            val customRingKey = settings.ringKeyCode
            val ringDesc = if (customRingKey != null) {
                stringResource(R.string.settings_ring_key_custom, customRingKey)
            } else {
                stringResource(R.string.settings_ring_key_default, "Volume Up / Down")
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_ring_key_mapping_label)) },
                supportingContent = { Text(ringDesc) },
                trailingContent = {
                    Row {
                        TextButton(onClick = onStartLearnRingKey) {
                            Text(stringResource(R.string.settings_ring_key_learn))
                        }
                        if (customRingKey != null) {
                            TextButton(onClick = onResetRingKey) {
                                Text(stringResource(R.string.settings_ring_key_reset))
                            }
                        }
                    }
                },
            )
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_approved_glasses_title))
            if (approvedGlasses.isEmpty()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_approved_glasses_empty)) },
                )
            } else {
                approvedGlasses.forEach { item ->
                    ListItem(
                        headlineContent = {
                            Text(
                                if (item.isLegacy) {
                                    stringResource(R.string.settings_approved_glasses_legacy_device, item.shortCode)
                                } else {
                                    stringResource(R.string.settings_approved_glasses_device, item.shortCode)
                                },
                            )
                        },
                        supportingContent = {
                            Text(item.mac ?: item.tokenHex.orEmpty())
                        },
                        trailingContent = {
                            TextButton(onClick = { onRemoveApprovedGlasses(item) }) {
                                Text(stringResource(R.string.settings_approved_glasses_remove))
                            }
                        },
                    )
                }
            }
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_backup))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_backup_export_title)) },
                supportingContent = { Text(stringResource(R.string.settings_backup_export_subtitle)) },
                trailingContent = {
                    TextButton(
                        onClick = {
                            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            exportLauncher.launch("rideflux_backup_$dateStr.zip")
                        },
                    ) {
                        Text(stringResource(R.string.action_export))
                    }
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_backup_import_title)) },
                supportingContent = { Text(stringResource(R.string.settings_backup_import_subtitle)) },
                trailingContent = {
                    TextButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed", "*/*"))
                        },
                    ) {
                        Text(stringResource(R.string.action_import))
                    }
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

/**
 * Glasses transport, mirrored from the scanner screen.
 *
 * It used to live only on the scanner, which is the one screen a rider
 * leaves as soon as they are connected — so the setting that decides
 * whether the HUD can be reached at all was unreachable exactly when
 * they went looking for it.
 */
@Composable
private fun GlassesLinkModeItem(
    linkMode: GlassesLinkMode,
    onSelectLinkMode: (GlassesLinkMode) -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.glasses_connection)) },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        },
    )
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
