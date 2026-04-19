/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.hud

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
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Lightbulb
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
import com.rideflux.app.ui.dashboard.DashboardUiState
import com.rideflux.app.ui.dashboard.DashboardViewModel
import com.rideflux.domain.telemetry.RideMode
import kotlin.math.roundToInt

/**
 * High-contrast, glance-friendly HUD surface intended for **Rokid AR
 * glasses**. Shares [DashboardViewModel] with the phone dashboard
 * so the rider sees exactly the same telemetry on both surfaces; the
 * repository ref-counts the underlying [com.rideflux.domain.connection.WheelConnection]
 * so opening the HUD does not spawn a second GATT session.
 *
 * Design principles:
 *  - Pure black background — the AR optics subtract pixels; black
 *    pixels are fully transparent to the user's real view.
 *  - Massive absolute-value speed readout centred on the optical
 *    axis — 220 sp at the primary chromaticity the glasses render
 *    most crisply (electric cyan).
 *  - Only essentials: speed, battery %, voltage, headlight and
 *    pedals-mode icons.
 *  - Tap anywhere to exit back to the phone dashboard.
 */
@Composable
fun HudRoute(
    onNavigateUp: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HudScreen(uiState = uiState, onExit = onNavigateUp)
}

@Composable
fun HudScreen(
    uiState: DashboardUiState,
    onExit: () -> Unit,
) {
    // Hard-coded pure-black surface: transparent on AR optics, high
    // OLED contrast on the phone fallback.
    Scaffold(containerColor = Color.Black) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(innerPadding)
                .pointerInput(Unit) {
                    // Single tap anywhere dismisses the HUD. We
                    // don't use a dedicated back button because the
                    // glasses typically have no screen-edge affordances.
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            onExit()
                        }
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                SpeedBlock(speedKmh = uiState.speedKmh)
                BatteryBlock(
                    percent = uiState.batteryPercent,
                    voltageV = uiState.voltageV,
                )
                StatusIconsRow(
                    headlightOn = uiState.headlightOn,
                    rideMode = uiState.rideMode,
                )
            }
        }
    }
}

// ---------- Building blocks --------------------------------------------

/** Electric cyan — renders crisply on Rokid waveguide optics. */
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
        // Absolute value — the rider reads magnitude, not direction.
        // (The VM already applies abs(); we apply it again here so
        // the HUD stays correct even when driven directly in previews.)
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
private fun StatusIconsRow(headlightOn: Boolean, rideMode: RideMode?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudStatusTile(
            iconContent = {
                Icon(
                    imageVector = if (headlightOn) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                    contentDescription = if (headlightOn) "Headlight on" else "Headlight off",
                    tint = if (headlightOn) HudCyan else HudWhite.copy(alpha = 0.5f),
                    modifier = Modifier.size(56.dp),
                )
            },
            label = if (headlightOn) "ON" else "OFF",
            labelColor = if (headlightOn) HudCyan else HudWhite.copy(alpha = 0.6f),
        )
        HudStatusTile(
            iconContent = {
                Icon(
                    Icons.Filled.Speed,
                    contentDescription = "Pedals mode",
                    tint = HudCyan,
                    modifier = Modifier.size(56.dp),
                )
            },
            label = rideMode?.label?.ifBlank { null } ?: "—",
            labelColor = HudWhite,
        )
    }
}

@Composable
private fun HudStatusTile(
    iconContent: @Composable () -> Unit,
    label: String,
    labelColor: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        iconContent()
        Spacer(Modifier.size(6.dp))
        Text(
            label,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = labelColor,
            textAlign = TextAlign.Center,
        )
    }
}
