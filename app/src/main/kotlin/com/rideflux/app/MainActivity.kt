/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.rememberNavController
import com.rideflux.app.bridge.BridgeService
import com.rideflux.app.bridge.BridgeState
import com.rideflux.app.navigation.RideFluxNavHost
import com.rideflux.app.ui.permission.BlePermissionGate
import com.rideflux.app.ui.theme.RideFluxTheme
import com.rideflux.app.ui.settings.RingKeyLearner
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
                        val pendingAuth by BridgeService.pendingAuthorization.collectAsState()
                        pendingAuth?.let { req ->
                            AlertDialog(
                                onDismissRequest = { BridgeService.rejectGlasses(context, req) },
                                title = { Text(stringResource(R.string.glasses_auth_dialog_title)) },
                                text = {
                                    val msg = if (req.isLegacy) {
                                        stringResource(R.string.glasses_auth_dialog_legacy_message, req.shortCode)
                                    } else {
                                        stringResource(R.string.glasses_auth_dialog_message, req.shortCode)
                                    }
                                    Text(msg)
                                },
                                confirmButton = {
                                    TextButton(onClick = { BridgeService.approveGlasses(context, req) }) {
                                        Text(stringResource(R.string.action_allow))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { BridgeService.rejectGlasses(context, req) }) {
                                        Text(stringResource(R.string.action_deny))
                                    }
                                },
                            )
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

    /**
     * Lets a BLE ring paired to the phone reveal and blank the glasses
     * HUD without the rider touching the screen.
     *
     * Rings of this class present themselves as an HID consumer-control
     * device and emit nothing but volume increment / decrement — the
     * AIVELA used for bring-up sends `0x0c00e9` / `0x0c00ea` as
     * instantaneous taps, with no hold duration to measure. So the two
     * directions are mapped to the two *states* rather than to a
     * toggle: blind on the finger, "up reveals, down blanks" is
     * unambiguous, while a toggle leaves the rider guessing which way a
     * missed press left the display.
     *
     * The keys are only swallowed while the bridge is actually running.
     * Consuming them unconditionally would break volume control for
     * anyone who just has the app open, and these are system media keys
     * — the interception only reaches us while this activity is in the
     * foreground.
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RingKeyLearner.isListening.value) {
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                RingKeyLearner.onKeyEvent(event.keyCode)
            }
            return true
        }

        val bridgeRunning = BridgeService.state.value != BridgeState.STOPPED
        if (bridgeRunning) {
            val customKey = settingsRepository.settings.value.ringKeyCode
            val isTargetKey = if (customKey != null) {
                event.keyCode == customKey
            } else {
                event.keyCode in DEFAULT_RING_KEYS
            }

            if (isTargetKey) {
                if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                    if (customKey != null) {
                        BridgeService.toggleHudVisible()
                    } else {
                        BridgeService.setHudVisible(event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        val DEFAULT_RING_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
    }
}
