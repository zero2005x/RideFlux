/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rideflux.app.MainActivity
import com.rideflux.app.R
import com.rideflux.app.di.ApplicationScope
import com.rideflux.domain.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Starts bridge standby after boot once Nearby Devices permission has been granted.
 *
 * Handles Android 14/15/16 foreground service restrictions defensively: if starting
 * BridgeService from BOOT_COMPLETED is rejected by the system (e.g. ForegroundServiceStartNotAllowedException),
 * catches the exception and posts a notification so the rider can tap to open RideFlux.
 */
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
                try {
                    BridgeService.startStandby(context)
                } catch (e: Exception) {
                    // Android 14 / 15 / 16 restricts foreground services started from BOOT_COMPLETED.
                    // Catch ForegroundServiceStartNotAllowedException, IllegalStateException, SecurityException.
                    Log.w(TAG, "boot auto-start prevented by system: ${e.message}", e)
                    notifyBootAutostartRestricted(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyBootAutostartRestricted(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot notify user of boot restriction: POST_NOTIFICATIONS not granted")
            return
        }
        val channelId = BridgeService.CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                context.getString(R.string.notification_channel_bridge),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_boot_autostart_title))
            .setContentText(context.getString(R.string.notification_boot_autostart_body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIF_ID_BOOT_RESTRICTED, notif)
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

    companion object {
        private const val TAG = "BridgeBootReceiver"
        const val NOTIF_ID_BOOT_RESTRICTED: Int = 4201

        /**
         * Pure predicate to determine whether an exception thrown during service launch
         * corresponds to an Android platform background start restriction.
         */
        fun isBackgroundStartRestriction(throwable: Throwable): Boolean {
            val name = throwable::class.java.name
            return name.contains("ForegroundServiceStartNotAllowedException") ||
                throwable is IllegalStateException ||
                throwable is SecurityException
        }
    }
}
