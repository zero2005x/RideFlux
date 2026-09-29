/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BridgeBootReceiverAndroidTest {
    private val receiver = BridgeBootReceiver()

    @Test
    fun standbyRequiresBothBluetoothPermissions() {
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context)
        shadow.grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(hasBridgePermissions(context))
        shadow.grantPermissions(Manifest.permission.BLUETOOTH_ADVERTISE)
        assertTrue(hasBridgePermissions(context))
        shadow.denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(hasBridgePermissions(context))
    }

    @Test
    fun restrictedBootPostsTapToOpenNotificationWhenAllowed() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifyBootAutostartRestricted(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(shadowOf(manager).getNotification(BridgeBootReceiver.NOTIF_ID_BOOT_RESTRICTED))
        assertNotNull(manager.getNotificationChannel(BridgeService.CHANNEL_ID))
    }

    private fun hasBridgePermissions(context: Context): Boolean =
        BridgeBootReceiver::class.java.getDeclaredMethod("hasBridgePermissions", Context::class.java)
            .apply { isAccessible = true }
            .invoke(receiver, context) as Boolean

    private fun notifyBootAutostartRestricted(context: Context) {
        BridgeBootReceiver::class.java.getDeclaredMethod("notifyBootAutostartRestricted", Context::class.java)
            .apply { isAccessible = true }
            .invoke(receiver, context)
    }
}
