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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlin.math.roundToInt

/**
 * High-contrast, glance-friendly HUD surface for the standalone
 * Rokid-AR-glasses APK.
 *
 * Design principles match :app's HUD variant:
 *  - Pure black background — AR optics subtract pixels; black is
 *    fully transparent to the rider's real view.
 *  - Massive absolute-value speed readout centred on the optical
 *    axis — electric cyan, the chromaticity the waveguides render
 *    most crisply.
 *  - Only essentials: speed, battery %, voltage, ride mode.
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "RideFlux HUD",
            color = HudCyan,
            fontSize = 64.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.size(16.dp))
        Text(
            "Launch with --es mac <BLE-ADDRESS>",
            color = HudWhite,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ConnectingMessage(targetMac: String?, state: ConnectionState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = state::class.simpleName.orEmpty().uppercase(),
            color = HudCyan,
            fontSize = 48.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = targetMac ?: "",
            color = HudWhite,
            fontSize = 22.sp,
        )
    }
}

@Composable
private fun ReadyHud(uiState: HudUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        SpeedBlock(speedKmh = uiState.speedKmh)
        BatteryBlock(percent = uiState.batteryPercent, voltageV = uiState.voltageV)
        StatusIconsRow(rideMode = uiState.rideMode)
    }
}

// ---------- Building blocks --------------------------------------------

/** Electric cyan — crisp on Rokid waveguide optics. */
private val HudCyan: Color = Color(0xFF00E5FF)

/** Bright green — healthy readouts. */
private val HudGreen: Color = Color(0xFF00FF88)

/** Stark white — neutral labels. */
private val HudWhite: Color = Color.White

/** Magenta-red — battery / critical alerts. */
private val HudRed: Color = Color(0xFFFF3366)

private const val LOW_BATTERY_THRESHOLD: Float = 20f

@Composable
private fun SpeedBlock(speedKmh: Float?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val display = speedKmh
            ?.let { kotlin.math.abs(it).roundToInt().toString() }
            ?: "--"
        Text(
            text = display,
            fontSize = 220.sp,
            fontWeight = FontWeight.Black,
            color = HudCyan,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "km/h",
            fontSize = 36.sp,
            fontWeight = FontWeight.Medium,
            color = HudWhite,
        )
    }
}

@Composable
private fun BatteryBlock(percent: Float?, voltageV: Float?) {
    val clamped = percent?.coerceIn(0f, 100f)
    val isLow = clamped != null && clamped <= LOW_BATTERY_THRESHOLD
    val tint = when {
        clamped == null -> HudWhite
        isLow -> HudRed
        else -> HudGreen
    }

    val pctText = clamped?.let { "${it.roundToInt()}%" } ?: "--%"
    val voltText = voltageV?.let { "%.1f V".format(it) } ?: "-- V"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Bolt,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = pctText,
            fontSize = 72.sp,
            fontWeight = FontWeight.Black,
            color = tint,
        )
        Spacer(Modifier.width(24.dp))
        Text(
            text = voltText,
            fontSize = 48.sp,
            fontWeight = FontWeight.SemiBold,
            color = HudWhite,
        )
    }
}

@Composable
private fun StatusIconsRow(rideMode: RideMode?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Speed,
                contentDescription = "Pedals mode",
                tint = HudCyan,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = rideMode?.label?.ifBlank { null } ?: "—",
                color = HudWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
