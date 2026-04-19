/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.rideflux.app.navigation.RideFluxNavHost
import com.rideflux.app.ui.permission.BlePermissionGate
import com.rideflux.app.ui.theme.RideFluxTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host for the RideFlux app.
 *
 * All navigation is owned by [RideFluxNavHost]; this activity only
 * wires the Compose content tree into [RideFluxTheme] and hands
 * control to the nav graph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RideFluxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    BlePermissionGate {
                        RideFluxNavHost()
                    }
                }
            }
        }
    }
}
