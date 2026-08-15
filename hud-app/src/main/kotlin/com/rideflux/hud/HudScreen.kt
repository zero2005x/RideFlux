/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.ElectricScooter
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.hud.source.BridgePeerCandidate
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * High-contrast, Rokid-style three-zone AR HUD surface for the
 * standalone glasses APK.
 *
 * Visual contract (mirrors the M365 Rokid HUD reference):
 *   ┌──────────────────────────────────────────────────────────────┐
 *   │  HH:mm   📞 87%        [    SPEED    ]      🛴 64%          │
 *   │  👓 92%  📶 GOOD       [   28  km/h  ]      ↔ 4.2 km        │
 *   │                                              ⏱ 00:14:32     │
 *   └──────────────────────────────────────────────────────────────┘
 *
 * Design principles:
 *  - Pure black background — AR optics subtract pixels; black is
 *    fully transparent to the rider's real-world view.
 *  - Strong neon accent (electric green) for at-a-glance focus
 *    items (clock, speed, battery), white at 70 % opacity for
 *    secondary labels.
 *  - No cards, no gradients, no borders except a single 1 dp
 *    rounded outline on the Retry button — anything heavier
 *    bleeds light through the visor.
 *  - Long-press anywhere to exit; a short tap is reserved for the
 *    Retry chip so the rider doesn't exit while trying to reconnect.
 *  - Optional horizontal mirroring via [MIRROR_HORIZONTALLY] for
 *    glasses optical paths that flip the image.
 */
@Composable
fun HudRoute(
    targetMac: String?,
    onExit: () -> Unit,
    onRetry: () -> Unit,
    viewModel: HudViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val candidates by viewModel.pairingCandidates.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }
    HudScreen(
        uiState = uiState,
        targetMac = targetMac,
        onExit = onExit,
        onRetry = onRetry,
        settingsOpen = settingsOpen,
        thresholds = settings.alertThresholds,
        pairingCandidates = candidates,
        onOpenSettings = { settingsOpen = true },
        onCloseSettings = { settingsOpen = false; viewModel.stopPhonePairing() },
        onStartPairing = viewModel::startPhonePairing,
        // The ViewModel rebuilds the bridge source in place with the new
        // allowlist MAC, so no activity restart is needed for pairing to
        // take effect.
        onPairPhone = viewModel::pairPhone,
        onSpeedLimit = viewModel::setSpeedLimit,
        onTemperatureLimit = viewModel::setTemperatureLimit,
        onLowBatteryLimit = viewModel::setLowBatteryLimit,
        onPwmLimit = viewModel::setPwmLimit,
    )
}

@Composable
fun HudScreen(
    uiState: HudUiState,
    targetMac: String?,
    onExit: () -> Unit,
    onRetry: () -> Unit,
    settingsOpen: Boolean = false,
    thresholds: AlertThresholds = AlertThresholds(),
    pairingCandidates: List<BridgePeerCandidate> = emptyList(),
    onOpenSettings: () -> Unit = {},
    onCloseSettings: () -> Unit = {},
    onStartPairing: () -> Unit = {},
    onPairPhone: (String) -> Unit = {},
    onSpeedLimit: (Float) -> Unit = {},
    onTemperatureLimit: (Float) -> Unit = {},
    onLowBatteryLimit: (Float) -> Unit = {},
    onPwmLimit: (Float) -> Unit = {},
) {
    // Use the latest onExit/onRetry even across recompositions so a
    // pointerInput(Unit) block never captures a stale lambda.
    val currentOnExit by rememberUpdatedState(onExit)
    val currentOnRetry by rememberUpdatedState(onRetry)
    val currentOnOpenSettings by rememberUpdatedState(onOpenSettings)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (MIRROR_HORIZONTALLY) Modifier.graphicsLayer(scaleX = -1f) else Modifier
            )
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { currentOnOpenSettings() })
            },
    ) {
        when (val phase = phaseOf(uiState)) {
            HudPhase.AwaitingTarget -> AwaitingTargetMessage(onRetry = currentOnRetry)
            HudPhase.WaitingPhone -> WaitingPhoneMessage()
            HudPhase.PhoneStandby -> PhoneStandbyMessage(
                phoneBatteryPercent = uiState.phoneBatteryPercent,
                glassesBatteryPercent = uiState.glassesBatteryPercent,
            )
            HudPhase.Scanning -> ScanningMessage()
            HudPhase.Connecting -> ConnectingMessage(targetMac = targetMac)
            is HudPhase.Disconnected -> DisconnectedMessage(reason = phase.reason, onRetry = currentOnRetry)
            HudPhase.Ready -> ReadyHud(uiState = uiState)
        }
        if (uiState.thresholdAlertActive && phaseOf(uiState) == HudPhase.Ready) {
            val flash by rememberAlertFlash()
            Box(
                Modifier.fillMaxSize().border(8.dp, Color.Red.copy(alpha = flash)),
            )
        }
        if (settingsOpen) {
            HudSettingsOverlay(
                thresholds = thresholds,
                candidates = pairingCandidates,
                onClose = onCloseSettings,
                onExit = currentOnExit,
                onStartPairing = onStartPairing,
                onPairPhone = onPairPhone,
                onSpeedLimit = onSpeedLimit,
                onTemperatureLimit = onTemperatureLimit,
                onLowBatteryLimit = onLowBatteryLimit,
                onPwmLimit = onPwmLimit,
            )
        }
    }
}

@Composable
private fun HudSettingsOverlay(
    thresholds: AlertThresholds,
    candidates: List<BridgePeerCandidate>,
    onClose: () -> Unit,
    onExit: () -> Unit,
    onStartPairing: () -> Unit,
    onPairPhone: (String) -> Unit,
    onSpeedLimit: (Float) -> Unit,
    onTemperatureLimit: (Float) -> Unit,
    onLowBatteryLimit: (Float) -> Unit,
    onPwmLimit: (Float) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ActionText("CLOSE", onClose)
            Text("HUD SETTINGS", color = HudGreen, fontWeight = FontWeight.Bold)
            ActionText("EXIT", onExit)
        }
        LimitRow("Speed", thresholds.speedLimitKmh, "km/h", 5f, onSpeedLimit)
        LimitRow("MOS temp", thresholds.temperatureLimitC, "°C", 5f, onTemperatureLimit)
        LimitRow("Low battery", thresholds.lowBatteryPercent, "%", 5f, onLowBatteryLimit)
        LimitRow("PWM", thresholds.pwmAlertPercent, "%", 5f, onPwmLimit)
        ActionText("PAIR WITH PHONE", onStartPairing)
        candidates.take(4).forEach { peer ->
            Text(
                "${peer.name ?: peer.address}  ${peer.rssi} dBm",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable { onPairPhone(peer.address) }.padding(5.dp),
            )
        }
    }
}

@Composable
private fun LimitRow(
    label: String,
    value: Float,
    unit: String,
    step: Float,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f), fontSize = 13.sp)
        ActionText("−", { onChange((value - step).coerceAtLeast(1f)) })
        Text("${value.roundToInt()} $unit", color = HudGreen, modifier = Modifier.width(82.dp), textAlign = TextAlign.Center)
        ActionText("+", { onChange((value + step).coerceAtMost(if (unit == "°C") 150f else 100f)) })
    }
}

@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = HudGreen,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.border(1.dp, HudGreen, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

// ---------- Phase derivation -------------------------------------------

private sealed class HudPhase {
    data object AwaitingTarget : HudPhase()
    data object WaitingPhone : HudPhase()
    data object PhoneStandby : HudPhase()
    data object Scanning : HudPhase()
    data object Connecting : HudPhase()
    data object Ready : HudPhase()
    data class Disconnected(val reason: String?) : HudPhase()
}

private fun phaseOf(s: HudUiState): HudPhase {
    if (s.awaitingTarget) return HudPhase.AwaitingTarget
    when (s.bridgeLinkState) {
        BridgeLinkState.NO_PHONE -> return HudPhase.WaitingPhone
        BridgeLinkState.PHONE_STANDBY -> return HudPhase.PhoneStandby
        BridgeLinkState.WHEEL_LIVE, null -> Unit
    }
    return when (val cs = s.connectionState) {
        ConnectionState.Connecting -> HudPhase.Scanning
        is ConnectionState.Handshaking -> HudPhase.Connecting
        ConnectionState.Ready -> HudPhase.Ready
        is ConnectionState.Failed -> HudPhase.Disconnected(cs.reason.name)
        ConnectionState.Disconnected -> HudPhase.Disconnected(reason = null)
    }
}

@Composable
private fun WaitingPhoneMessage() {
    val pulse by rememberPulse()
    CenteredStatus(
        title = "WAITING\nPHONE",
        titleColor = HudGreen,
        titleAlpha = pulse,
        subtitle = "Searching for RideFlux…",
        onRetry = {},
    )
}

@Composable
private fun PhoneStandbyMessage(
    phoneBatteryPercent: Int?,
    glassesBatteryPercent: Int?,
) {
    CenteredStatus(
        title = "PHONE\nCONNECTED",
        titleColor = HudGreen,
        subtitle = "Phone ${formatPercent(phoneBatteryPercent?.toFloat())}  ·  Glasses ${formatPercent(glassesBatteryPercent?.toFloat())}",
        onRetry = {},
    )
}

// ---------- Sub-screens (non-Ready) ------------------------------------

@Composable
private fun AwaitingTargetMessage(onRetry: () -> Unit) {
    CenteredStatus(
        // Pre-split into two lines: at 40 sp Black on the Rokid's
        // narrow viewport, "RIDEFLUX HUD" wraps mid-word and the
        // glyphs collide vertically. Explicit `\n` keeps the brand
        // mark stable across screen widths.
        title = "RIDEFLUX\nHUD",
        titleColor = HudGreen,
        subtitle = "Launch with --es mac <BLE-ADDRESS>",
        showRetry = true,
        retryLabel = "SCAN",
        onRetry = onRetry,
    )
}

@Composable
private fun ScanningMessage() {
    val pulse by rememberPulse()
    CenteredStatus(
        title = "SCANNING",
        titleColor = HudGreen,
        titleAlpha = pulse,
        subtitle = "Looking for vehicle…",
        onRetry = {},
    )
}

@Composable
private fun ConnectingMessage(targetMac: String?) {
    val pulse by rememberPulse()
    CenteredStatus(
        title = "CONNECTING",
        titleColor = HudGreen,
        titleAlpha = pulse,
        subtitle = targetMac.orEmpty(),
        onRetry = {},
    )
}

@Composable
private fun DisconnectedMessage(reason: String?, onRetry: () -> Unit) {
    CenteredStatus(
        title = "RIDEFLUX\nHUD",
        titleColor = HudGreen,
        subtitle = reason?.let { "OFFLINE · $it" } ?: "OFFLINE",
        subtitleColor = HudRed,
        showRetry = true,
        retryLabel = "RETRY",
        onRetry = onRetry,
    )
}

@Composable
private fun CenteredStatus(
    title: String,
    titleColor: Color,
    titleAlpha: Float = 1f,
    subtitle: String,
    subtitleColor: Color = HudWhiteSoft,
    showRetry: Boolean = false,
    retryLabel: String = "RETRY",
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = titleColor,
            // 40 sp comfortably fits "RIDEFLUX" on the Rokid's narrow
            // viewport without wrapping; single-word states
            // ("SCANNING", "CONNECTING") stay on one line.
            fontSize = 40.sp,
            // 1.15× the font size: prevents the "RIDEFLUX" / "HUD"
            // baselines from overlapping at FontWeight.Black, which
            // has glyphs taller than the default lineHeight on AOSP.
            lineHeight = 46.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(titleAlpha),
        )
        Spacer(Modifier.height(12.dp))
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                color = subtitleColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
        if (showRetry) {
            Spacer(Modifier.height(28.dp))
            RetryChip(label = retryLabel, onClick = onRetry)
        }
    }
}

@Composable
private fun RetryChip(label: String, onClick: () -> Unit) {
    // A flat outlined chip — Material3 buttons are too heavy for the
    // glasses optical pipeline, which prefers minimal fill / lots of
    // black.
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .border(width = 1.dp, color = HudGreen, shape = RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, tint = HudGreen, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = HudGreen,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---------- Ready HUD --------------------------------------------------

@Composable
private fun ReadyHud(uiState: HudUiState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // RV101 exposes 480 x 640 px at 240 dpi (320 dp wide).
            // Keep only a 4 dp edge inset and avoid the top reflection
            // band so the side stacks use the full optical width.
            .padding(
                start = HUD_HORIZONTAL_PADDING,
                end = HUD_HORIZONTAL_PADDING,
                top = HUD_TOP_PADDING,
                bottom = HUD_BOTTOM_PADDING,
            ),
    ) {
        LeftColumn(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(SIDE_COLUMN_WIDTH),
            phoneBatteryPercent = uiState.phoneBatteryPercent,
            glassesBatteryPercent = uiState.glassesBatteryPercent,
            signal = uiState.signalQuality,
        )
        CenterSpeed(
            modifier = Modifier
                .align(Alignment.Center)
                .width(CENTER_COLUMN_WIDTH),
            speedKmh = uiState.speedKmh,
        )
        RightColumn(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(SIDE_COLUMN_WIDTH),
            vehicleBatteryPercent = uiState.vehicleBatteryPercent,
            tripDistanceMetres = uiState.tripDistanceMetres,
            tripDurationSeconds = uiState.tripDurationSeconds,
        )

        // Stale-data flag floats top-centre so it stays out of the
        // way of the three primary columns.
        if (uiState.isStale) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = HudAmber,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "STALE",
                    color = HudAmber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ---------- Columns -----------------------------------------------------

@Composable
private fun LeftColumn(
    modifier: Modifier = Modifier,
    phoneBatteryPercent: Int?,
    glassesBatteryPercent: Int?,
    signal: SignalQuality,
) {
    val clock by rememberWallClock()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // Wall clock — primary item in this column. Tinted with the
        // RideFlux cyan brand colour so it doesn't visually compete
        // with the neon-green speed value in the centre column.
        IconLabelRow(
            icon = Icons.Filled.Schedule,
            iconTint = HudCyan,
            text = clock,
            textColor = HudCyan,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
        )
        // Phone battery (paired companion device).
        IconLabelRow(
            icon = Icons.Filled.Smartphone,
            iconTint = HudWhiteSoft,
            text = formatPercent(phoneBatteryPercent?.toFloat()),
            textColor = HudWhiteSoft,
            fontSize = 14.sp,
        )
        // Glasses battery (this device).
        IconLabelRow(
            icon = Icons.Filled.Visibility,
            iconTint = HudWhiteSoft,
            text = formatPercent(glassesBatteryPercent?.toFloat()),
            textColor = HudWhiteSoft,
            fontSize = 14.sp,
        )
        // BLE signal quality.
        SignalRow(signal = signal)
    }
}

@Composable
private fun SignalRow(signal: SignalQuality) {
    val (icon, tint, label) = when (signal) {
        SignalQuality.GOOD -> Triple(Icons.Filled.BluetoothConnected, HudGreen, "GOOD")
        SignalQuality.WEAK -> Triple(Icons.AutoMirrored.Filled.BluetoothSearching, HudAmber, "WEAK")
        SignalQuality.NONE -> Triple(Icons.Filled.BluetoothDisabled, HudRed, "OFFLINE")
    }
    IconLabelRow(
        icon = icon,
        iconTint = tint,
        text = label,
        textColor = tint,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun CenterSpeed(modifier: Modifier = Modifier, speedKmh: Float?) {
    // Guard non-finite telemetry: roundToInt() maps NaN → 0 and
    // +Infinity → Int.MAX_VALUE, which would render "0" or
    // "2147483647" on the primary HUD readout.
    val display = speedKmh
        ?.takeIf { it.isFinite() }
        ?.coerceIn(0f, 999f)
        ?.roundToInt()
        ?.toString() ?: "--"
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = display,
            // 72 sp fits three digits in the exact 320 dp RV101
            // viewport without pushing either side stack inward.
            fontSize = 72.sp,
            fontWeight = FontWeight.Black,
            color = HudGreen,
            textAlign = TextAlign.Center,
            letterSpacing = (-1).sp,
        )
        Text(
            text = "km/h",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = HudWhiteSoft,
        )
    }
}

@Composable
private fun RightColumn(
    modifier: Modifier = Modifier,
    vehicleBatteryPercent: Float?,
    tripDistanceMetres: Int?,
    tripDurationSeconds: Long?,
) {
    val pct = vehicleBatteryPercent?.takeIf { it.isFinite() }?.coerceIn(0f, 100f)
    val tint = batteryTint(pct)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // Right column is end-aligned, so place the icon after the
        // text — keeps the numerals flush with the right edge and
        // the icons forming a clean vertical column on the inside.
        IconLabelRow(
            icon = Icons.Filled.ElectricScooter,
            iconTint = tint,
            text = pct?.let { "${it.roundToInt()}%" } ?: "--%",
            textColor = tint,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            iconAfter = true,
        )
        if (tripDistanceMetres != null) {
            IconLabelRow(
                icon = Icons.Filled.Straighten,
                iconTint = HudWhiteSoft,
                text = formatDistance(tripDistanceMetres),
                textColor = HudWhiteSoft,
                fontSize = 14.sp,
                iconAfter = true,
            )
        }
        if (tripDurationSeconds != null) {
            IconLabelRow(
                icon = Icons.Filled.Timer,
                iconTint = HudWhiteSoft,
                text = formatDuration(tripDurationSeconds),
                textColor = HudWhiteSoft,
                fontSize = 14.sp,
                iconAfter = true,
            )
        }
    }
}

// ---------- Building blocks --------------------------------------------

@Composable
private fun IconLabelRow(
    icon: ImageVector,
    iconTint: Color,
    text: String,
    textColor: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Bold,
    iconAfter: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!iconAfter) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            color = textColor,
            fontSize = fontSize,
            fontWeight = fontWeight,
        )
        if (iconAfter) {
            Spacer(Modifier.width(4.dp))
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Local HH:mm wall clock ticking every 15 seconds. Avoids pulling
 * in `java.time`-Gradle-desugaring surprises by using [LocalTime]
 * directly — we already enable core-library desugaring for minSdk 26.
 */
@Composable
private fun rememberWallClock(): androidx.compose.runtime.State<String> =
    produceState(initialValue = formatNow()) {
        while (true) {
            value = formatNow()
            delay(15_000L)
        }
    }

/** 0.4 → 1.0 alpha ramp at ~1 Hz, used by Scanning / Connecting placards. */
@Composable
private fun rememberPulse(): androidx.compose.runtime.State<Float> {
    val transition = rememberInfiniteTransition(label = "hudPulse")
    return transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hudPulseAlpha",
    )
}

/** 2 Hz red-border flash used while a local safety threshold remains active. */
@Composable
private fun rememberAlertFlash(): androidx.compose.runtime.State<Float> {
    val transition = rememberInfiniteTransition(label = "hudAlertFlash")
    return transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 250, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hudAlertBorderAlpha",
    )
}

private fun formatNow(): String =
    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))

private fun formatPercent(value: Float?): String =
    value?.takeIf { it.isFinite() }?.coerceIn(0f, 100f)?.let { "${it.roundToInt()}%" } ?: "--%"

private fun formatDistance(metres: Int): String =
    if (metres >= 1000) "%.1f km".format(Locale.ROOT, metres / 1000f) else "$metres m"

private fun formatDuration(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    val h = safeSeconds / 3600L
    val m = (safeSeconds % 3600L) / 60L
    val s = safeSeconds % 60L
    // Locale.ROOT pins ASCII digits — comma-decimal / localized-digit
    // locales are ambiguous on a glance HUD.
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%02d:%02d".format(Locale.ROOT, m, s)
}

private fun batteryTint(percent: Float?): Color = when {
    percent == null -> HudWhiteSoft
    percent <= CRITICAL_BATTERY_THRESHOLD -> HudRed
    percent <= LOW_BATTERY_THRESHOLD -> HudAmber
    // Normal vehicle battery uses RideFlux cyan, distinct from the
    // neon green reserved for the centre speed value. Spec calls
    // this out as "green/cyan for normal battery".
    else -> HudCyan
}

// ---------- Palette / layout constants ---------------------------------

/** Electric green — crisp on waveguide optics, matches reference HUDs.
 *  Reserved for the dominant centre-column speed value. */
private val HudGreen: Color = Color(0xFF00FF88)

/** RideFlux brand cyan — secondary accent for clock and normal vehicle
 *  battery. Picked to contrast with [HudGreen] on AR waveguides while
 *  remaining within the cool / electric palette in the spec. */
private val HudCyan: Color = Color(0xFF00E5FF)

/** Amber — warning state for low battery / weak signal / stale data. */
private val HudAmber: Color = Color(0xFFFFB300)

/** Magenta-red — critical battery / disconnected. */
private val HudRed: Color = Color(0xFFFF3366)

/** Stark white at 70 % opacity — neutral secondary labels. */
private val HudWhiteSoft: Color = Color.White.copy(alpha = 0.70f)

private const val LOW_BATTERY_THRESHOLD: Float = 20f
private const val CRITICAL_BATTERY_THRESHOLD: Float = 8f

private val HUD_HORIZONTAL_PADDING = 4.dp
private val HUD_TOP_PADDING = 32.dp
private val HUD_BOTTOM_PADDING = 20.dp
private val SIDE_COLUMN_WIDTH = 88.dp
private val CENTER_COLUMN_WIDTH = 128.dp

/**
 * Set to `true` if the deployed glasses optical path mirrors the
 * Android display horizontally. Kept as a compile-time flag so it
 * can be flipped per-build without any runtime configuration.
 */
private const val MIRROR_HORIZONTALLY: Boolean = false
