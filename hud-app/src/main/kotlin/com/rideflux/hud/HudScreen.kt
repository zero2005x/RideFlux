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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
    val hudVisible by viewModel.hudVisible.collectAsStateWithLifecycle()
    val isLearningRingKey by viewModel.isLearningRingKey.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }
    HudScreen(
        uiState = uiState,
        hudVisible = hudVisible,
        mirrorHorizontally = settings.hudMirrorHorizontally,
        targetMac = targetMac,
        onExit = onExit,
        onRetry = onRetry,
        settingsOpen = settingsOpen,
        thresholds = settings.alertThresholds,
        pairingCandidates = candidates,
        isLearningRingKey = isLearningRingKey,
        ringKeyCode = settings.ringKeyCode,
        onOpenSettings = { settingsOpen = true },
        onCloseSettings = {
            settingsOpen = false
            viewModel.stopPhonePairing()
            viewModel.stopLearningRingKey()
        },
        onStartPairing = viewModel::startPhonePairing,
        // The ViewModel rebuilds the bridge source in place with the new
        // pairing token, so no activity restart is needed for pairing to
        // take effect.
        onPairPhone = viewModel::pairPhone,
        onSpeedLimit = viewModel::setSpeedLimit,
        onTemperatureLimit = viewModel::setTemperatureLimit,
        onLowBatteryLimit = viewModel::setLowBatteryLimit,
        onPwmLimit = viewModel::setPwmLimit,
        onToggleMirror = { viewModel.setHudMirrorHorizontally(!settings.hudMirrorHorizontally) },
        onStartLearnRing = viewModel::startLearningRingKey,
        onResetRingKey = viewModel::resetRingKey,
    )
}

@Composable
fun HudScreen(
    uiState: HudUiState,
    targetMac: String?,
    onExit: () -> Unit,
    onRetry: () -> Unit,
    hudVisible: Boolean = true,
    mirrorHorizontally: Boolean = false,
    settingsOpen: Boolean = false,
    thresholds: AlertThresholds = AlertThresholds(),
    pairingCandidates: List<BridgePeerCandidate> = emptyList(),
    isLearningRingKey: Boolean = false,
    ringKeyCode: Int? = null,
    onOpenSettings: () -> Unit = {},
    onCloseSettings: () -> Unit = {},
    onStartPairing: () -> Unit = {},
    onPairPhone: (BridgePeerCandidate) -> Unit = {},
    onSpeedLimit: (Float) -> Unit = {},
    onTemperatureLimit: (Float) -> Unit = {},
    onLowBatteryLimit: (Float) -> Unit = {},
    onPwmLimit: (Float) -> Unit = {},
    onToggleMirror: () -> Unit = {},
    onStartLearnRing: () -> Unit = {},
    onResetRingKey: () -> Unit = {},
) {
    // Use the latest onExit/onRetry even across recompositions so a
    // pointerInput(Unit) block never captures a stale lambda.
    val currentOnExit by rememberUpdatedState(onExit)
    val currentOnRetry by rememberUpdatedState(onRetry)
    val currentOnOpenSettings by rememberUpdatedState(onOpenSettings)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (mirrorHorizontally) Modifier.graphicsLayer(scaleX = -1f) else Modifier
            )
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { currentOnOpenSettings() })
            },
    ) {
        val dims = remember(maxWidth, maxHeight) {
            HudLayoutDimensions.calculate(maxWidth.value, maxHeight.value)
        }
        // Blanked by a ring press (here or on the phone). The link, the
        // frame pipeline and this activity all stay exactly as they
        // were — only the drawing stops — so revealing it again is
        // instant. A live safety threshold is the one thing that still
        // breaks through: a rider who blanked the display to cut glare
        // must not thereby silence an overspeed or over-temperature
        // warning.
        val suppressed = !hudVisible && !uiState.thresholdAlertActive
        when (val phase = if (suppressed) null else phaseOf(uiState)) {
            null -> Unit
            HudPhase.AwaitingTarget -> AwaitingTargetMessage(onRetry = currentOnRetry)
            HudPhase.WaitingPhone -> WaitingPhoneMessage(onPair = {
                currentOnOpenSettings()
                onStartPairing()
            })
            HudPhase.PhoneStandby -> PhoneStandbyMessage(
                phoneBatteryPercent = uiState.phoneBatteryPercent,
                glassesBatteryPercent = uiState.glassesBatteryPercent,
            )
            HudPhase.Scanning -> ScanningMessage()
            HudPhase.Connecting -> ConnectingMessage(targetMac = targetMac)
            is HudPhase.Disconnected -> DisconnectedMessage(reason = phase.reason, onRetry = currentOnRetry)
            HudPhase.Ready -> ReadyHud(uiState = uiState, dims = dims)
        }
        if (uiState.thresholdAlertActive && phaseOf(uiState) == HudPhase.Ready && !suppressed) {
            val flash by rememberAlertFlash()
            Box(
                Modifier.fillMaxSize().border(8.dp, Color.Red.copy(alpha = flash)),
            )
        }
        if (settingsOpen) {
            HudSettingsOverlay(
                thresholds = thresholds,
                candidates = pairingCandidates,
                mirrorHorizontally = mirrorHorizontally,
                isLearningRingKey = isLearningRingKey,
                ringKeyCode = ringKeyCode,
                onClose = onCloseSettings,
                onExit = currentOnExit,
                onStartPairing = onStartPairing,
                onPairPhone = onPairPhone,
                onSpeedLimit = onSpeedLimit,
                onTemperatureLimit = onTemperatureLimit,
                onLowBatteryLimit = onLowBatteryLimit,
                onPwmLimit = onPwmLimit,
                onToggleMirror = onToggleMirror,
                onStartLearnRing = onStartLearnRing,
                onResetRingKey = onResetRingKey,
            )
        }
    }
}

@Composable
private fun HudSettingsOverlay(
    thresholds: AlertThresholds,
    candidates: List<BridgePeerCandidate>,
    mirrorHorizontally: Boolean,
    isLearningRingKey: Boolean,
    ringKeyCode: Int?,
    onClose: () -> Unit,
    onExit: () -> Unit,
    onStartPairing: () -> Unit,
    onPairPhone: (BridgePeerCandidate) -> Unit,
    onSpeedLimit: (Float) -> Unit,
    onTemperatureLimit: (Float) -> Unit,
    onLowBatteryLimit: (Float) -> Unit,
    onPwmLimit: (Float) -> Unit,
    onToggleMirror: () -> Unit,
    onStartLearnRing: () -> Unit,
    onResetRingKey: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ActionText(stringResource(R.string.hud_settings_close), onClose)
            Text(
                stringResource(R.string.hud_settings_title),
                color = HudGreen,
                fontWeight = FontWeight.Bold,
            )
            ActionText(stringResource(R.string.hud_settings_exit), onExit)
        }
        val celsius = stringResource(R.string.unit_celsius)
        val percent = stringResource(R.string.unit_percent)
        LimitRow(
            stringResource(R.string.hud_limit_speed),
            thresholds.speedLimitKmh,
            stringResource(R.string.unit_kmh),
            5f,
            SPEED_AND_PERCENT_MAX,
            onSpeedLimit,
        )
        LimitRow(
            stringResource(R.string.hud_limit_mos_temp),
            thresholds.temperatureLimitC,
            celsius,
            5f,
            TEMPERATURE_MAX,
            onTemperatureLimit,
        )
        LimitRow(
            stringResource(R.string.hud_limit_low_battery),
            thresholds.lowBatteryPercent,
            percent,
            5f,
            SPEED_AND_PERCENT_MAX,
            onLowBatteryLimit,
        )
        LimitRow(
            stringResource(R.string.hud_limit_pwm),
            thresholds.pwmAlertPercent,
            percent,
            5f,
            SPEED_AND_PERCENT_MAX,
            onPwmLimit,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.hud_settings_mirror), color = Color.White, fontSize = 13.sp)
            ActionText(
                if (mirrorHorizontally) stringResource(R.string.hud_settings_on) else stringResource(R.string.hud_settings_off),
                onToggleMirror,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ringLabel = when {
                isLearningRingKey -> stringResource(R.string.hud_settings_ring_listening)
                ringKeyCode != null -> stringResource(R.string.hud_settings_ring_learned, ringKeyCode)
                else -> stringResource(R.string.hud_settings_ring_default)
            }
            Text(
                stringResource(R.string.hud_settings_ring_key, ringLabel),
                color = if (isLearningRingKey) HudAmber else Color.White,
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isLearningRingKey) {
                    ActionText(stringResource(R.string.hud_settings_ring_learn), onStartLearnRing)
                    if (ringKeyCode != null) {
                        ActionText(stringResource(R.string.hud_settings_ring_reset), onResetRingKey)
                    }
                }
            }
        }
        ActionText(stringResource(R.string.hud_settings_pair_with_phone), onStartPairing)
        candidates.take(4).forEach { peer ->
            val label = peer.shortCode?.let { "CODE $it" }
                ?: peer.name
                ?: peer.address
            Text(
                stringResource(R.string.hud_peer_row, label, peer.rssi),
                color = if (peer.shortCode != null) HudGreen else Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = MIN_TOUCH_TARGET)
                    .clickable { onPairPhone(peer) }
                    .padding(5.dp),
            )
        }
    }
}

/** Upper bound for speed (km/h) and percentage limits. */
private const val SPEED_AND_PERCENT_MAX = 100f

/** Upper bound for the MOS temperature limit, in °C. */
private const val TEMPERATURE_MAX = 150f

@Composable
private fun LimitRow(
    label: String,
    value: Float,
    unit: String,
    step: Float,
    // Passed explicitly rather than inferred from `unit`: the unit label is
    // display text, and inferring a safety bound from it would silently
    // change behaviour if that text ever moved.
    max: Float,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f), fontSize = 13.sp)
        ActionText(stringResource(R.string.hud_step_decrement)) {
            onChange((value - step).coerceAtLeast(1f))
        }
        Text(
            stringResource(R.string.hud_limit_value, value.roundToInt(), unit),
            color = HudGreen,
            modifier = Modifier.width(82.dp),
            textAlign = TextAlign.Center,
        )
        ActionText(stringResource(R.string.hud_step_increment)) {
            onChange((value + step).coerceAtMost(max))
        }
    }
}

/**
 * Minimum touch target.
 *
 * These controls are driven from the glasses' own touchpad or ring,
 * where the rider cannot see their finger against the target at all —
 * so the 48 dp Material minimum is a floor here, not a nicety. The
 * outline stays visually small; only the hit area grows, which keeps
 * the surface as dark as the AR optics need.
 */
private val MIN_TOUCH_TARGET = 48.dp

@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = MIN_TOUCH_TARGET, minHeight = MIN_TOUCH_TARGET)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = HudGreen,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .border(1.dp, HudGreen, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

// ---------- Phase derivation -------------------------------------------

/**
 * Maps a failure onto something a rider can act on, in three buckets.
 *
 * The HUD used to print the enum constant — a glance at 40 km/h would
 * show `INTERNAL` or `CHECKSUM_STORM`, which is untranslated, shouty
 * and tells the rider nothing. Seven reasons collapse to three because
 * only three distinct actions exist at the roadside: the radio dropped,
 * the wheel is not talking to us, or the app itself is broken.
 *
 * A null reason is a plain disconnect with no diagnosis; callers render
 * the reason-free string for it, so this returns the internal label
 * only to keep the lookup unconditional.
 */
@androidx.annotation.StringRes
private fun humanReasonRes(reason: ConnectionState.Failed.Reason?): Int = when (reason) {
    ConnectionState.Failed.Reason.BLE_LINK_LOST,
    ConnectionState.Failed.Reason.GATT_ERROR,
    -> R.string.hud_offline_link_lost

    ConnectionState.Failed.Reason.HANDSHAKE_TIMEOUT,
    ConnectionState.Failed.Reason.UNKNOWN_FAMILY,
    ConnectionState.Failed.Reason.CHECKSUM_STORM,
    ConnectionState.Failed.Reason.AUTHENTICATION_FAILED,
    -> R.string.hud_offline_handshake

    // `else` rather than naming INTERNAL: Reason is documented as
    // extensible, and a new constant must not stop this compiling.
    else -> R.string.hud_offline_internal
}

private sealed class HudPhase {
    data object AwaitingTarget : HudPhase()
    data object WaitingPhone : HudPhase()
    data object PhoneStandby : HudPhase()
    data object Scanning : HudPhase()
    data object Connecting : HudPhase()
    data object Ready : HudPhase()
    data class Disconnected(val reason: ConnectionState.Failed.Reason?) : HudPhase()
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
        is ConnectionState.Failed -> HudPhase.Disconnected(cs.reason)
        ConnectionState.Disconnected -> HudPhase.Disconnected(reason = null)
    }
}

@Composable
private fun WaitingPhoneMessage(onPair: () -> Unit) {
    val pulse by rememberPulse()
    CenteredStatus(
        title = stringResource(R.string.hud_waiting_phone_title),
        titleColor = HudGreen,
        titleAlpha = pulse,
        subtitle = stringResource(R.string.hud_waiting_phone_subtitle),
        showRetry = true,
        retryLabel = stringResource(R.string.hud_action_pair_phone),
        onRetry = onPair,
    )
}

@Composable
private fun PhoneStandbyMessage(
    phoneBatteryPercent: Int?,
    glassesBatteryPercent: Int?,
) {
    CenteredStatus(
        title = stringResource(R.string.hud_phone_connected_title),
        titleColor = HudGreen,
        subtitle = stringResource(
            R.string.hud_phone_standby_subtitle,
            formatPercent(phoneBatteryPercent?.toFloat()),
            formatPercent(glassesBatteryPercent?.toFloat()),
        ),
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
        title = stringResource(R.string.hud_brand_title),
        titleColor = HudGreen,
        subtitle = stringResource(R.string.hud_awaiting_target_subtitle),
        showRetry = true,
        retryLabel = stringResource(R.string.hud_action_scan),
        onRetry = onRetry,
    )
}

@Composable
private fun ScanningMessage() {
    val pulse by rememberPulse()
    CenteredStatus(
        title = stringResource(R.string.hud_scanning_title),
        titleColor = HudGreen,
        titleAlpha = pulse,
        subtitle = stringResource(R.string.hud_scanning_subtitle),
        onRetry = {},
    )
}

@Composable
private fun ConnectingMessage(targetMac: String?) {
    val pulse by rememberPulse()
    CenteredStatus(
        title = stringResource(R.string.hud_connecting_title),
        titleColor = HudGreen,
        titleAlpha = pulse,
        subtitle = targetMac.orEmpty(),
        onRetry = {},
    )
}

@Composable
private fun DisconnectedMessage(reason: ConnectionState.Failed.Reason?, onRetry: () -> Unit) {
    // Both variants are resolved unconditionally: stringResource is a
    // composable call and must not sit behind a data-dependent branch.
    val offline = stringResource(R.string.hud_offline)
    val reasonLabel = stringResource(humanReasonRes(reason))
    val offlineWithReason = stringResource(R.string.hud_offline_reason, reasonLabel)
    CenteredStatus(
        title = stringResource(R.string.hud_brand_title),
        titleColor = HudGreen,
        subtitle = if (reason != null) offlineWithReason else offline,
        subtitleColor = HudRed,
        showRetry = true,
        retryLabel = stringResource(R.string.hud_action_retry),
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
    // Only read when showRetry is true; every such caller supplies it.
    retryLabel: String = "",
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
private fun ReadyHud(uiState: HudUiState, dims: HudLayoutDimensions) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = dims.horizontalPaddingDp.dp,
                end = dims.horizontalPaddingDp.dp,
                top = dims.topPaddingDp.dp,
                bottom = dims.bottomPaddingDp.dp,
            ),
    ) {
        LeftColumn(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(dims.sideColumnWidthDp.dp),
            phoneBatteryPercent = uiState.phoneBatteryPercent,
            glassesBatteryPercent = uiState.glassesBatteryPercent,
            signal = uiState.signalQuality,
            dims = dims,
        )
        CenterSpeed(
            modifier = Modifier
                .align(Alignment.Center)
                .width(dims.centerColumnWidthDp.dp),
            speedKmh = uiState.speedKmh,
            dims = dims,
        )
        RightColumn(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(dims.sideColumnWidthDp.dp),
            vehicleBatteryPercent = uiState.vehicleBatteryPercent,
            tripDistanceMetres = uiState.tripDistanceMetres,
            tripDurationSeconds = uiState.tripDurationSeconds,
            dims = dims,
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
                    text = stringResource(R.string.hud_stale),
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
    dims: HudLayoutDimensions,
) {
    val clock by rememberWallClock()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(dims.verticalSpacingDp.dp),
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
            fontSize = dims.clockFontSizeSp.sp,
            iconSize = dims.iconSizeDp.dp,
            fontWeight = FontWeight.Black,
        )
        // Phone battery (paired companion device).
        IconLabelRow(
            icon = Icons.Filled.Smartphone,
            iconTint = HudWhiteSoft,
            text = formatPercent(phoneBatteryPercent?.toFloat()),
            textColor = HudWhiteSoft,
            fontSize = dims.labelFontSizeSp.sp,
            iconSize = dims.iconSizeDp.dp,
        )
        // Glasses battery (this device).
        IconLabelRow(
            icon = Icons.Filled.Visibility,
            iconTint = HudWhiteSoft,
            text = formatPercent(glassesBatteryPercent?.toFloat()),
            textColor = HudWhiteSoft,
            fontSize = dims.labelFontSizeSp.sp,
            iconSize = dims.iconSizeDp.dp,
        )
        // BLE signal quality.
        SignalRow(signal = signal, dims = dims)
    }
}

@Composable
private fun SignalRow(signal: SignalQuality, dims: HudLayoutDimensions? = null) {
    val (icon, tint, labelRes) = when (signal) {
        SignalQuality.GOOD ->
            Triple(Icons.Filled.BluetoothConnected, HudGreen, R.string.hud_signal_good)
        SignalQuality.WEAK ->
            Triple(Icons.AutoMirrored.Filled.BluetoothSearching, HudAmber, R.string.hud_signal_weak)
        SignalQuality.NONE ->
            Triple(Icons.Filled.BluetoothDisabled, HudRed, R.string.hud_signal_none)
    }
    val label = stringResource(labelRes)
    IconLabelRow(
        icon = icon,
        iconTint = tint,
        text = label,
        textColor = tint,
        fontSize = dims?.labelFontSizeSp?.sp ?: 14.sp,
        iconSize = dims?.iconSizeDp?.dp ?: 16.dp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun CenterSpeed(
    modifier: Modifier = Modifier,
    speedKmh: Float?,
    dims: HudLayoutDimensions,
) {
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
            fontSize = dims.speedFontSizeSp.sp,
            fontWeight = FontWeight.Black,
            color = HudGreen,
            textAlign = TextAlign.Center,
            letterSpacing = (-1).sp,
        )
        Text(
            text = stringResource(R.string.unit_kmh),
            fontSize = dims.speedUnitFontSizeSp.sp,
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
    dims: HudLayoutDimensions,
) {
    val pct = vehicleBatteryPercent?.takeIf { it.isFinite() }?.coerceIn(0f, 100f)
    val tint = batteryTint(pct)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(dims.verticalSpacingDp.dp),
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
            fontSize = dims.batteryFontSizeSp.sp,
            iconSize = dims.iconSizeDp.dp,
            fontWeight = FontWeight.Black,
            iconAfter = true,
        )
        if (tripDistanceMetres != null) {
            IconLabelRow(
                icon = Icons.Filled.Straighten,
                iconTint = HudWhiteSoft,
                text = formatDistance(tripDistanceMetres),
                textColor = HudWhiteSoft,
                fontSize = dims.labelFontSizeSp.sp,
                iconSize = dims.iconSizeDp.dp,
                iconAfter = true,
            )
        }
        if (tripDurationSeconds != null) {
            IconLabelRow(
                icon = Icons.Filled.Timer,
                iconTint = HudWhiteSoft,
                text = formatDuration(tripDurationSeconds),
                textColor = HudWhiteSoft,
                fontSize = dims.labelFontSizeSp.sp,
                iconSize = dims.iconSizeDp.dp,
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
    iconSize: Dp = 16.dp,
    fontWeight: FontWeight = FontWeight.Bold,
    iconAfter: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!iconAfter) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
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
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
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
