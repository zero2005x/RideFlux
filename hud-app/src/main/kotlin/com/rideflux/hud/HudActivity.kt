/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single launcher activity for the HUD APK.
 *
 * Accepts one optional intent extra:
 *   * [EXTRA_MAC] — string MAC address of the wheel to connect to.
 *     If absent, the HUD shows a "no target" instruction screen
 *     (the rider can launch with e.g.
 *     `adb shell am start -n com.rideflux.hud/.HudActivity
 *       --es mac AA:BB:CC:DD:EE:FF`).
 */
@AndroidEntryPoint
class HudActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mac: String? = intent?.getStringExtra(EXTRA_MAC)
        setContent {
            // We intentionally do not wrap in the :app theme. The HUD
            // surface is pure black-on-cyan for the AR optics; we
            // only need a Material surface to host the typography.
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black,
                ) {
                    HudRoute(targetMac = mac, onExit = { finish() })
                }
            }
        }
    }

    companion object {
        /** Intent extra key for the target wheel's BLE MAC address. */
        const val EXTRA_MAC: String = "mac"
    }
}
