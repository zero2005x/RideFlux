/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.permission

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NotificationsHintTest {

    @Test
    fun hintAppearsOnlyWhileTheBridgeRunsWithNotificationsOff() {
        assertTrue(shouldShowNotificationsHint(bridgeActive = true, notificationsEnabled = false))
        assertFalse(shouldShowNotificationsHint(bridgeActive = true, notificationsEnabled = true))
        // With the bridge off nothing can ask for approval, so there is nothing to warn about.
        assertFalse(shouldShowNotificationsHint(bridgeActive = false, notificationsEnabled = false))
        assertFalse(shouldShowNotificationsHint(bridgeActive = false, notificationsEnabled = true))
    }

    @Test
    fun theAppsOwnNotificationPageComesFirstAndTheDetailsPageIsTheFallback() {
        val intents = notificationSettingsIntents("com.example.app")

        assertEquals(2, intents.size)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intents[0].action)
        assertEquals("com.example.app", intents[0].getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents[1].action)
        assertEquals("package:com.example.app", intents[1].dataString)
    }

    @Test
    fun opensTheNotificationPageWhenTheDeviceHasIt() {
        val context = RecordingContext(unresolvable = emptySet())

        openNotificationSettings(context)

        assertEquals(listOf(Settings.ACTION_APP_NOTIFICATION_SETTINGS), context.started.map { it.action })
        assertEquals(context.packageName, context.started.single().getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun fallsBackToTheDetailsPageWhenTheNotificationPageDoesNotResolve() {
        val context = RecordingContext(unresolvable = setOf(Settings.ACTION_APP_NOTIFICATION_SETTINGS))

        openNotificationSettings(context)

        assertEquals(
            listOf(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            context.started.map { it.action },
        )
    }

    @Test
    fun doesNothingWhenNoSettingsPageResolves() {
        val context = RecordingContext(
            unresolvable = setOf(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
        )

        openNotificationSettings(context)

        assertEquals(2, context.started.size)
    }

    /** Records every attempted start and refuses the actions in [unresolvable], as a ROM without them would. */
    private class RecordingContext(private val unresolvable: Set<String>) :
        ContextWrapper(RuntimeEnvironment.getApplication() as Context) {
        val started = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            started += intent
            if (intent.action in unresolvable) throw ActivityNotFoundException(intent.action)
        }
    }
}
