/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.rideflux.app.ui.dashboard.components.RideFluxColors
import com.rideflux.app.ui.dashboard.pages.BmsPage
import com.rideflux.app.ui.dashboard.pages.EventsPage
import com.rideflux.app.ui.dashboard.pages.GraphPage
import com.rideflux.app.ui.dashboard.pages.MainGaugePage
import com.rideflux.app.ui.dashboard.pages.MapPage
import com.rideflux.app.ui.dashboard.pages.ParametersPage
import com.rideflux.app.ui.dashboard.pages.TripsPage
import com.rideflux.app.recording.RecordingService
import com.rideflux.app.recording.RecordingUiState
import com.rideflux.domain.alert.ThresholdAlert
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.telemetry.RideMode
import com.rideflux.domain.telemetry.WheelAlert
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stateful entry point wired to Hilt. Pulls everything off the
 * ViewModel and hands it to the stateless [DashboardScreen].
 */
@Composable
fun DashboardRoute(
    onNavigateUp: () -> Unit,
    onNavigateToHud: () -> Unit,
    onOpenTrip: (Long) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val alertLog by viewModel.alertLog.collectAsStateWithLifecycle()
    val activeAlert by viewModel.activeAlert.collectAsStateWithLifecycle()
    val recordingState by RecordingService.state.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    var locationPermissionGranted by remember {
        mutableStateOf(RecordingService.hasLocationPermission(context))
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> locationPermissionGranted = granted }
    LaunchedEffect(Unit) {
        if (!locationPermissionGranted) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val bridgedMac by com.rideflux.app.bridge.BridgeService.activeMac
        .collectAsStateWithLifecycle()
    val bridgeActive = bridgedMac == viewModel.address
    val startBridge = {
        com.rideflux.app.bridge.BridgeService.start(
            context = context,
            mac = viewModel.address,
            family = viewModel.expectedFamily,
        )
    }
    val advertisePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startBridge()
    }

    DashboardScreen(
        uiState = uiState,
        history = history,
        events = alertLog,
        activeAlert = activeAlert,
        recordingState = recordingState,
        locationPermissionGranted = locationPermissionGranted,
        bridgeActive = bridgeActive,
        onNavigateUp = onNavigateUp,
        onNavigateToHud = onNavigateToHud,
        onOpenTrip = onOpenTrip,
        onStopRecording = { RecordingService.stop(context) },
        onToggleBridge = {
            if (bridgeActive) {
                com.rideflux.app.bridge.BridgeService.clearTarget(context)
            } else {
                val needsAdvertisePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                    ) != PackageManager.PERMISSION_GRANTED
                if (needsAdvertisePermission) {
                    advertisePermissionLauncher.launch(Manifest.permission.BLUETOOTH_ADVERTISE)
                } else {
                    startBridge()
                }
            }
        },
        onSetHeadlight = viewModel::setHeadlight,
        onSetPedalsMode = viewModel::setPedalsMode,
        onBeep = viewModel::beep,
    )
}

/**
 * Top-level pages exposed by the dashboard pager. Order is the
 * swipe order; the [title] renders above the page indicator.
 */
private enum class DashboardPage(val title: String) {
    Main("Dashboard"),
    Graph("Graph"),
    Parameters("Parameters"),
    Bms("Battery"),
    Trips("Trips"),
    Events("Events"),
    Map("Map"),
}

/**
 * Stateless dashboard. Wraps a [HorizontalPager] of seven pages
 * with a Wheellog-style top toolbar (live HH:mm:ss clock, vehicle
 * model, HUD link) and a coloured page indicator at the bottom.
 *
 * Page 1 (Main) also hosts the wheel-control card (headlight,
 * pedals mode, beep) so riders don't have to hunt for it during
 * use.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    history: List<TelemetrySample>,
    events: List<TimedAlert>,
    activeAlert: DashboardAlert?,
    recordingState: RecordingUiState = RecordingUiState(),
    locationPermissionGranted: Boolean = false,
    bridgeActive: Boolean = false,
    onNavigateUp: () -> Unit,
    onNavigateToHud: () -> Unit = {},
    onOpenTrip: (Long) -> Unit = {},
    onStopRecording: () -> Unit = {},
    onToggleBridge: () -> Unit = {},
    onSetHeadlight: (Boolean) -> Unit = {},
    onSetPedalsMode: (Int) -> Unit = {},
    onBeep: () -> Unit = {},
) {
    val localView = LocalView.current
    DisposableEffect(uiState.keepScreenOnDashboard) {
        val previous = localView.keepScreenOn
        localView.keepScreenOn = uiState.keepScreenOnDashboard
        onDispose { localView.keepScreenOn = previous }
    }
    val pagerState = rememberPagerState(pageCount = { DashboardPage.values().size })
    val currentPage by remember {
        derivedStateOf { DashboardPage.values()[pagerState.currentPage] }
    }
    val clock by produceClockState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = clock,
                            style = MaterialTheme.typography.titleLarge,
                            color = RideFluxColors.Cyan,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = uiState.identity?.modelName
                                ?: uiState.identity?.address
                                ?: currentPage.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    ConnectionDot(state = uiState.connectionState)
                    Spacer(Modifier.width(8.dp))
                    // Bridge toggle — starts/stops the foreground
                    // service that relays telemetry to the AR
                    // glasses over the :data:bridge GATT channel.
                    IconButton(onClick = onToggleBridge) {
                        Icon(
                            imageVector = if (bridgeActive)
                                Icons.Filled.CastConnected
                            else
                                Icons.Filled.Cast,
                            contentDescription = if (bridgeActive)
                                "Remove wheel from HUD bridge"
                            else
                                "Send wheel to HUD bridge",
                            tint = if (bridgeActive)
                                RideFluxColors.Neon
                            else
                                MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onNavigateToHud) {
                        Icon(
                            Icons.Filled.Tv,
                            contentDescription = "Open AR HUD",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            PageIndicator(
                pageCount = DashboardPage.values().size,
                selected = pagerState.currentPage,
                label = currentPage.title,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AlertBanner(
                alert = activeAlert,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { pageIndex ->
                when (DashboardPage.values()[pageIndex]) {
                    DashboardPage.Main -> Column(modifier = Modifier.fillMaxSize()) {
                        MainGaugePage(
                            state = uiState,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        ControlsCard(
                            headlightOn = uiState.headlightOn,
                            rideMode = uiState.rideMode,
                            enabled = uiState.connectionState == ConnectionState.Ready,
                            onSetHeadlight = onSetHeadlight,
                            onSetPedalsMode = onSetPedalsMode,
                            onBeep = onBeep,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    DashboardPage.Graph -> GraphPage(history = history, useMetric = uiState.useMetric)
                    DashboardPage.Parameters -> ParametersPage(state = uiState)
                    DashboardPage.Bms -> BmsPage(state = uiState)
                    DashboardPage.Trips -> TripsPage(
                        state = uiState,
                        recordingState = recordingState,
                        onStopRecording = onStopRecording,
                        onOpenTrip = onOpenTrip,
                    )
                    DashboardPage.Events -> EventsPage(events = events)
                    DashboardPage.Map -> MapPage(
                        samples = recordingState.samples,
                        locationPermissionGranted = locationPermissionGranted,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Top-bar / chrome composables
// ---------------------------------------------------------------------

@Composable
private fun produceClockState(): androidx.compose.runtime.State<String> {
    val state = remember { mutableStateOf(currentClockString()) }
    LaunchedEffect(Unit) {
        while (true) {
            state.value = currentClockString()
            delay(500L)
        }
    }
    return state
}

private val CLOCK_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun currentClockString(): String = CLOCK_FORMAT.format(Date())

/**
 * Tiny coloured dot summarising the connection state in the top
 * bar. Cyan/green = ready, orange = handshaking/connecting,
 * red = failed, mute = disconnected.
 */
@Composable
private fun ConnectionDot(state: ConnectionState) {
    val tint = when (state) {
        ConnectionState.Ready -> RideFluxColors.Neon
        ConnectionState.Connecting -> RideFluxColors.Warning
        is ConnectionState.Handshaking -> RideFluxColors.Warning
        ConnectionState.Disconnected -> RideFluxColors.Mute
        is ConnectionState.Failed -> RideFluxColors.Danger
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(tint),
    )
}

/**
 * Bottom page indicator: dots tinted with the RideFlux cyan accent
 * for the active page and a faint mute tone for the rest, with
 * the current page's title rendered above so the user always
 * knows where they are.
 */
@Composable
private fun PageIndicator(pageCount: Int, selected: Int, label: String) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until pageCount) {
                    val active = i == selected
                    Box(
                        modifier = Modifier
                            .size(if (active) 10.dp else 6.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                if (active) RideFluxColors.Cyan
                                else RideFluxColors.Mute.copy(alpha = 0.6f),
                            ),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Active alert banner (shared across every page)
// ---------------------------------------------------------------------

@Composable
fun AlertBanner(alert: DashboardAlert?, modifier: Modifier = Modifier) {
    // Keep the last non-null alert around so AnimatedVisibility's exit
    // transition has content to animate when `alert` becomes null;
    // returning early would make the banner vanish abruptly.
    var lastAlert by remember { mutableStateOf<DashboardAlert?>(null) }
    if (alert != null) lastAlert = alert
    AnimatedVisibility(
        visible = alert != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val a = alert ?: lastAlert ?: return@AnimatedVisibility
        val (title, body, severe) = describeAlert(a)
        val container = if (severe) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            RideFluxColors.Warning.copy(alpha = 0.25f)
        }
        val content = if (severe) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            color = container,
            contentColor = content,
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(body, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private data class AlertDescription(val title: String, val body: String, val severe: Boolean)

private fun describeAlert(alert: DashboardAlert): AlertDescription = when (alert) {
    is DashboardAlert.Wheel -> describeWheelAlert(alert.value)
    is DashboardAlert.Threshold -> when (val threshold = alert.value) {
        is ThresholdAlert.Overspeed -> AlertDescription(
            "SPEED LIMIT",
            "${"%.1f".format(Locale.US, threshold.speedKmh)} km/h exceeds ${"%.1f".format(Locale.US, threshold.limitKmh)} km/h",
            true,
        )
        is ThresholdAlert.OverTemperature -> AlertDescription(
            "MOS TEMPERATURE",
            "${"%.1f".format(Locale.US, threshold.temperatureC)}°C exceeds ${"%.1f".format(Locale.US, threshold.limitC)}°C",
            true,
        )
        is ThresholdAlert.LowBattery -> AlertDescription(
            "LOW BATTERY",
            "${"%.0f".format(Locale.US, threshold.percent)}% is below ${"%.0f".format(Locale.US, threshold.limitPercent)}%",
            true,
        )
        is ThresholdAlert.PwmLoad -> AlertDescription(
            "PWM LOAD",
            "${"%.0f".format(Locale.US, threshold.pwmPercent)}% exceeds ${"%.0f".format(Locale.US, threshold.limitPercent)}%",
            true,
        )
    }
}

private fun describeWheelAlert(alert: WheelAlert): AlertDescription = when (alert) {
    is WheelAlert.TiltBack -> AlertDescription(
        title = "TILT-BACK",
        body = "Speed ${"%.0f".format(Locale.US, alert.speedKmh)} km/h · limit ${"%.0f".format(Locale.US, alert.limit)} km/h",
        severe = true,
    )
    is WheelAlert.SpeedCutoff -> AlertDescription(
        title = "SPEED CUTOFF",
        body = "Motor cut at ${"%.0f".format(Locale.US, alert.speedKmh)} km/h",
        severe = true,
    )
    is WheelAlert.LowBattery -> AlertDescription(
        title = "Low battery",
        body = "Pack voltage ${"%.1f".format(Locale.US, alert.voltageV)} V",
        severe = false,
    )
    is WheelAlert.OverTemperature -> AlertDescription(
        title = "Over temperature",
        body = "${alert.source.name}" + (alert.temperatureC?.let { " · ${"%.0f".format(Locale.US, it)}°C" } ?: ""),
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

// ---------------------------------------------------------------------
// Wheel controls (kept on the main page for at-glance access)
// ---------------------------------------------------------------------

private data class PedalsModePreset(val code: Int, val label: String)

private val DEFAULT_PEDALS_PRESETS: List<PedalsModePreset> = listOf(
    PedalsModePreset(0, "Soft"),
    PedalsModePreset(1, "Medium"),
    PedalsModePreset(2, "Hard"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlsCard(
    headlightOn: Boolean,
    rideMode: RideMode?,
    enabled: Boolean,
    onSetHeadlight: (Boolean) -> Unit,
    onSetPedalsMode: (Int) -> Unit,
    onBeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Headlight",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Switch(
                    checked = headlightOn,
                    onCheckedChange = onSetHeadlight,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    ),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DEFAULT_PEDALS_PRESETS.forEach { preset ->
                    val selected = rideMode?.code == preset.code
                    FilterChip(
                        selected = selected,
                        onClick = { onSetPedalsMode(preset.code) },
                        enabled = enabled,
                        label = { Text(preset.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onBeep,
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        Icons.Filled.Campaign,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Beep", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Re-exports kept for source compatibility with previews / tests
// that previously imported `SpeedometerDisplay` / `BatteryGauge`
// from this package.
// ---------------------------------------------------------------------

@Composable
@Suppress("unused")
fun SpeedometerDisplay(speedKmh: Float?, modifier: Modifier = Modifier) {
    com.rideflux.app.ui.dashboard.components.SpeedGauge(
        speedKmh = speedKmh, modifier = modifier,
    )
}

@Composable
@Suppress("unused")
fun BatteryGauge(percent: Float?, voltageV: Float?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        com.rideflux.app.ui.dashboard.components.BatteryBar(percent = percent)
        Spacer(Modifier.height(6.dp))
        Text(
            text = voltageV?.let { "%.1f V".format(Locale.US, it) } ?: "-- V",
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}
