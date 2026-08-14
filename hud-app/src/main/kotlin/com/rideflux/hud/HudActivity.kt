/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.rideflux.hud.permission.BlePermissionGate
import com.rideflux.hud.storage.HudMacStore
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

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

    // Accessed before setContent so Hilt DI graph initialisation and
    // BLE class loading happen during onCreate() — before the
    // Choreographer starts counting frames — rather than blocking the
    // first Compose composition.
    private val viewModel: HudViewModel by viewModels()

    @Inject lateinit var macStore: HudMacStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // RV101 turns the display off after roughly ten seconds even
        // while this activity remains the resumed task. That moves the
        // Compose lifecycle below STARTED and cancels the BLE scan.
        // A riding HUD must keep its display and scan alive until the
        // rider explicitly exits the activity.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        // Touch the delegate to trigger eager ViewModel + Hilt wiring.
        viewModel
        // Resolve MAC: explicit intent extra wins; otherwise fall
        // back to the last-used MAC persisted by HudViewModel. This
        // lets the rider re-launch from the launcher icon without
        // re-issuing `adb am start --es mac ...`.
        val mac: String? = (intent?.getStringExtra(EXTRA_MAC) ?: macStore.readMac())
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf(BluetoothAdapter::checkBluetoothAddress)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black,
                ) {
                    BlePermissionGate {
                        HudRoute(
                            targetMac = mac,
                            onExit = { finish() },
                            // Restart the activity to re-trigger the connection
                            // pipeline. Cheap because the BLE repository is a
                            // process-singleton, so only the ViewModel and one
                            // GATT session are recreated. Start the new intent
                            // BEFORE finishing so the launch isn't consumed by
                            // the dying instance.
                            onRetry = {
                                val retry = Intent(this, HudActivity::class.java)
                                    .putExtras(intent)
                                startActivity(retry)
                                finish()
                            },
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }

    companion object {
        /** Intent extra key for the target wheel's BLE MAC address. */
        const val EXTRA_MAC: String = "mac"

        // Note: the family/source intent extras are read by
        // HudViewModel via HudViewModel.KEY_FAMILY / KEY_SOURCE (same
        // string values). They are intentionally not duplicated here so
        // the two sets of keys cannot drift apart.
    }
}
