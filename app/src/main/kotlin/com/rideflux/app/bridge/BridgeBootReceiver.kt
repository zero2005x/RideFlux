/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.rideflux.app.di.ApplicationScope
import com.rideflux.domain.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Starts bridge standby after boot once Nearby Devices permission has been granted. */
@AndroidEntryPoint
class BridgeBootReceiver : BroadcastReceiver() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        applicationScope.launch {
            try {
                if (!settingsRepository.current().bridgeAutostart) return@launch
                if (!hasBridgePermissions(context)) {
                    Log.w(TAG, "boot auto-start skipped: Bluetooth permissions are not granted")
                    return@launch
                }
                BridgeService.startStandby(context)
            } finally {
                pending.finish()
            }
        }
    }

    private fun hasBridgePermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "BridgeBootReceiver"
    }
}
