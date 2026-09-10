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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.rideflux.app.R
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
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> locationPermissionGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true }
    LaunchedEffect(Unit) {
        if (!locationPermissionGranted) {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
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
 * swipe order; [titleRes] renders above the page indicator.
 */
private enum class DashboardPage(@androidx.annotation.StringRes val titleRes: Int) {
    Main(R.string.page_dashboard),
    Graph(R.string.page_graph),
    Parameters(R.string.page_parameters),
    Bms(R.string.page_battery),
    Trips(R.string.page_trips),
    Events(R.string.page_events),
    Map(R.string.page_map),
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
                                ?: stringResource(currentPage.titleRes),
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
                            contentDescription = stringResource(R.string.action_back),
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
                            contentDescription = stringResource(
                                if (bridgeActive) R.string.dashboard_bridge_remove
                                else R.string.dashboard_bridge_send,
                            ),
                            tint = if (bridgeActive)
                                RideFluxColors.Neon
                            else
                                MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onNavigateToHud) {
                        Icon(
                            Icons.Filled.Tv,
                            contentDescription = stringResource(R.string.dashboard_open_ar_hud),
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
                label = stringResource(currentPage.titleRes),
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
                // uppercase() with the default locale: the page labels are
                // translated, and casing rules are language-specific.
                text = label.uppercase(Locale.getDefault()),
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

/**
 * Numbers stay on [Locale.US] so the decimal separator matches the units
 * ("45.0 km/h", never "45,0 km/h" beside an ASCII unit), while the
 * surrounding sentence comes from the translated string resources.
 */
private fun Float.fmt1(): String = "%.1f".format(Locale.US, this)
private fun Float.fmt0(): String = "%.0f".format(Locale.US, this)

@Composable
private fun describeAlert(alert: DashboardAlert): AlertDescription = when (alert) {
    is DashboardAlert.Wheel -> describeWheelAlert(alert.value)
    is DashboardAlert.Threshold -> when (val threshold = alert.value) {
        is ThresholdAlert.Overspeed -> AlertDescription(
            stringResource(R.string.alert_speed_limit_title),
            stringResource(
                R.string.alert_speed_limit_body,
                threshold.speedKmh.fmt1(),
                threshold.limitKmh.fmt1(),
            ),
            true,
        )
        is ThresholdAlert.OverTemperature -> AlertDescription(
            stringResource(R.string.alert_mos_temperature_title),
            stringResource(
                R.string.alert_mos_temperature_body,
                threshold.temperatureC.fmt1(),
                threshold.limitC.fmt1(),
            ),
            true,
        )
        is ThresholdAlert.LowBattery -> AlertDescription(
            stringResource(R.string.alert_low_battery_title),
            stringResource(
                R.string.alert_low_battery_body,
                threshold.percent.fmt0(),
                threshold.limitPercent.fmt0(),
            ),
            true,
        )
        is ThresholdAlert.PwmLoad -> AlertDescription(
            stringResource(R.string.alert_pwm_load_title),
            stringResource(
                R.string.alert_pwm_load_body,
                threshold.pwmPercent.fmt0(),
                threshold.limitPercent.fmt0(),
            ),
            true,
        )
    }
}

@Composable
private fun describeWheelAlert(alert: WheelAlert): AlertDescription = when (alert) {
    is WheelAlert.TiltBack -> AlertDescription(
        title = stringResource(R.string.alert_tilt_back_title),
        body = stringResource(
            R.string.alert_tilt_back_body,
            alert.speedKmh.fmt0(),
            alert.limit.fmt0(),
        ),
        severe = true,
    )
    is WheelAlert.SpeedCutoff -> AlertDescription(
        title = stringResource(R.string.alert_speed_cutoff_title),
        body = stringResource(R.string.alert_speed_cutoff_body, alert.speedKmh.fmt0()),
        severe = true,
    )
    is WheelAlert.LowBattery -> AlertDescription(
        title = stringResource(R.string.alert_wheel_low_battery_title),
        body = stringResource(R.string.alert_wheel_low_battery_body, alert.voltageV.fmt1()),
        severe = false,
    )
    is WheelAlert.OverTemperature -> AlertDescription(
        title = stringResource(R.string.alert_over_temperature_title),
        // The source is a protocol enum name — deliberately untranslated.
        body = alert.source.name + (alert.temperatureC?.let { " · ${it.fmt0()}°C" } ?: ""),
        severe = true,
    )
    is WheelAlert.FallDown -> AlertDescription(
        title = stringResource(R.string.alert_fall_title),
        body = stringResource(R.string.alert_fall_body),
        severe = true,
    )
    is WheelAlert.FaultSetChanged -> AlertDescription(
        title = stringResource(R.string.alert_fault_set_title),
        body = faultSetSummary(alert),
        severe = alert.added.isNotEmpty(),
    )
    is WheelAlert.Raw -> AlertDescription(
        title = stringResource(
            R.string.alert_raw_title,
            alert.domain,
            alert.code.toString(16).uppercase(Locale.ROOT),
        ),
        body = stringResource(R.string.alert_raw_body, alert.payload.size),
        severe = false,
    )
}

/**
 * "+2 faults -1 cleared", or the fallback when the wheel reported a change
 * with neither set populated. Both fragments are resolved unconditionally
 * because [stringResource] may not be called inside a data-dependent branch.
 */
@Composable
internal fun faultSetSummary(alert: WheelAlert.FaultSetChanged): String {
    val added = stringResource(R.string.alert_faults_added, alert.added.size)
    val cleared = stringResource(R.string.alert_faults_cleared, alert.removed.size)
    val fallback = stringResource(R.string.alert_updated)
    return listOfNotNull(
        added.takeIf { alert.added.isNotEmpty() },
        cleared.takeIf { alert.removed.isNotEmpty() },
    ).joinToString(" ").ifBlank { fallback }
}

// ---------------------------------------------------------------------
// Wheel controls (kept on the main page for at-glance access)
// ---------------------------------------------------------------------

private data class PedalsModePreset(val code: Int, @androidx.annotation.StringRes val labelRes: Int)

private val DEFAULT_PEDALS_PRESETS: List<PedalsModePreset> = listOf(
    PedalsModePreset(0, R.string.pedals_soft),
    PedalsModePreset(1, R.string.pedals_medium),
    PedalsModePreset(2, R.string.pedals_hard),
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
                    stringResource(R.string.controls_headlight),
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
                        label = { Text(stringResource(preset.labelRes), fontSize = 12.sp) },
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
                    Text(
                        stringResource(R.string.action_beep),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
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
            text = voltageV?.let { "%.1f".format(Locale.US, it) }
                ?.plus(" ${stringResource(R.string.unit_volt)}")
                ?: "${stringResource(R.string.value_unavailable)} ${stringResource(R.string.unit_volt)}",
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}
