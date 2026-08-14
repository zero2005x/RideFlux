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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
import com.rideflux.app.bridge.BridgeService
import com.rideflux.app.navigation.RideFluxNavHost
import com.rideflux.app.ui.permission.BlePermissionGate
import com.rideflux.app.ui.theme.RideFluxTheme
import com.rideflux.domain.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity host for the RideFlux app.
 *
 * All navigation is owned by [RideFluxNavHost]; this activity only
 * wires the Compose content tree into [RideFluxTheme] and hands
 * control to the nav graph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val navController = rememberNavController()
            RideFluxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    BlePermissionGate {
                        val context = LocalContext.current
                        // Standby is the default once Nearby Devices has
                        // been granted. This also covers first install / app
                        // update, where BOOT_COMPLETED has not occurred yet.
                        LaunchedEffect(Unit) {
                            if (settingsRepository.current().bridgeAutostart) {
                                BridgeService.startStandby(context.applicationContext)
                            }
                        }
                        RideFluxNavHost(
                            navController = navController,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
