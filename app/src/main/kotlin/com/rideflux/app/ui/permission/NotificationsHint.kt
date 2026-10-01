/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.permission

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Whether to tell the rider that notifications are off.
 *
 * The bridge works without them, but a pairing request from the glasses is then shown on the
 * app's own screen only, so it goes unseen while the app is in the background and lapses after a
 * minute. Worth saying only while the bridge runs: with it off there is nothing to approve.
 */
internal fun shouldShowNotificationsHint(bridgeActive: Boolean, notificationsEnabled: Boolean): Boolean =
    bridgeActive && !notificationsEnabled

/**
 * The system screens on which this app's notifications can be turned back on, best match first.
 *
 * The permission dialog stops appearing once it was declined (twice, or after a "don't ask
 * again"), so these screens are the only way back. The app's own notification page comes first;
 * the general app-details page is the fallback for a ROM that does not resolve it.
 */
internal fun notificationSettingsIntents(packageName: String): List<Intent> = listOf(
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
)

/** Opens the first of [notificationSettingsIntents] that resolves; does nothing if none does. */
internal fun openNotificationSettings(context: Context) {
    for (intent in notificationSettingsIntents(context.packageName)) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Not available on this device: fall through to the next, more general, screen.
        }
    }
}
