/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.telemetry.RideMode
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * High-contrast, glance-friendly HUD surface for the standalone
 * Rokid-AR-glasses APK.
 *
 * Design principles:
 *  - Pure black background — AR optics subtract pixels; black is
 *    fully transparent to the rider's real view.
 *  - All content is anchored to the bottom half of the phone screen
 *    so, when reflected into the glasses, it appears low in the
 *    rider's field of vision rather than blocking the road ahead.
 *  - Layout mirrors the Xiaomi M365 HUD-glasses convention: wall
 *    clock + wheel battery in the left column, huge absolute-value
 *    speed readout dead-centre, ride mode + trip metrics in the
 *    right column.
 *  - Electric green — high luminance on waveguide optics and the
 *    same chromaticity enthusiast HUD apps converge on.
 *  - Tap anywhere to exit.
 */
@Composable
fun HudRoute(
    targetMac: String?,
    onExit: () -> Unit,
    viewModel: HudViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HudScreen(uiState = uiState, targetMac = targetMac, onExit = onExit)
}

@Composable
fun HudScreen(
    uiState: HudUiState,
    targetMac: String?,
    onExit: () -> Unit,
) {
    Scaffold(containerColor = Color.Black) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(innerPadding)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            onExit()
                        }
                    }
                },
        ) {
            when {
                uiState.awaitingTarget -> NoTargetMessage()
                uiState.connectionState != ConnectionState.Ready ->
                    ConnectingMessage(
                        targetMac = targetMac,
                        state = uiState.connectionState,
                    )
                else -> ReadyHud(uiState = uiState)
            }
        }
    }
}

// ---------- Sub-screens -------------------------------------------------

@Composable
private fun NoTargetMessage() {
    // Anchored to the bottom half so it reflects low in the rider's
    // field of view, matching the telemetry HUD's vertical position.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "RideFlux HUD",
            color = HudGreen,
            fontSize = 56.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Launch with --es mac <BLE-ADDRESS>",
            color = HudWhite,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ConnectingMessage(targetMac: String?, state: ConnectionState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state::class.simpleName.orEmpty().uppercase(),
            color = HudGreen,
            fontSize = 48.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = targetMac ?: "",
            color = HudWhite,
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Three-column telemetry grid concentrated in the lower half of the
 * screen. The top half is intentionally left pure black so that,
 * when the phone is mounted face-up under a transparent visor, the
 * user's real-world view is not occluded.
 */
@Composable
private fun ReadyHud(uiState: HudUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            LeftColumn(
                batteryPercent = uiState.batteryPercent,
                voltageV = uiState.voltageV,
            )
            CenterSpeed(speedKmh = uiState.speedKmh)
            RightColumn(rideMode = uiState.rideMode)
        }
    }
}

// ---------- Columns -----------------------------------------------------

@Composable
private fun LeftColumn(batteryPercent: Float?, voltageV: Float?) {
    val clock by rememberWallClock()
    val clamped = batteryPercent?.coerceIn(0f, 100f)
    val isLow = clamped != null && clamped <= LOW_BATTERY_THRESHOLD
    val batteryTint = when {
        clamped == null -> HudWhite
        isLow -> HudRed
        else -> HudGreen
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        IconLabelRow(
            icon = { Icon(Icons.Filled.Schedule, null, tint = HudGreen, modifier = Modifier.size(22.dp)) },
            text = clock,
            textColor = HudGreen,
            fontSize = 28.sp,
        )
        IconLabelRow(
            icon = { Icon(Icons.Filled.BatteryStd, null, tint = batteryTint, modifier = Modifier.size(22.dp)) },
            text = clamped?.let { "${it.roundToInt()}%" } ?: "--%",
            textColor = batteryTint,
            fontSize = 28.sp,
        )
        IconLabelRow(
            icon = { Icon(Icons.Filled.Speed, null, tint = HudGreen, modifier = Modifier.size(22.dp)) },
            text = voltageV?.let { "%.1fV".format(it) } ?: "-- V",
            textColor = HudGreen,
            fontSize = 24.sp,
        )
    }
}

@Composable
private fun CenterSpeed(speedKmh: Float?) {
    val display = speedKmh
        ?.let { "%.1f".format(kotlin.math.abs(it)) }
        ?: "--"
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = display,
            fontSize = 96.sp,
            fontWeight = FontWeight.Black,
            color = HudGreen,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "km/h",
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            color = HudGreen,
        )
    }
}

@Composable
private fun RightColumn(rideMode: RideMode?) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        IconLabelRow(
            icon = { Icon(Icons.Filled.DirectionsBike, null, tint = HudGreen, modifier = Modifier.size(22.dp)) },
            text = rideMode?.label?.ifBlank { null } ?: "—",
            textColor = HudGreen,
            fontSize = 28.sp,
        )
    }
}

// ---------- Building blocks --------------------------------------------

@Composable
private fun IconLabelRow(
    icon: @Composable () -> Unit,
    text: String,
    textColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = textColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
        )
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

private fun formatNow(): String =
    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

// ---------- Palette ----------------------------------------------------

/** Electric green — crisp on waveguide optics, matches reference HUDs. */
private val HudGreen: Color = Color(0xFF00FF88)

/** Stark white — neutral secondary labels. */
private val HudWhite: Color = Color.White

/** Magenta-red — low-battery alert only. */
private val HudRed: Color = Color(0xFFFF3366)

private const val LOW_BATTERY_THRESHOLD: Float = 20f
