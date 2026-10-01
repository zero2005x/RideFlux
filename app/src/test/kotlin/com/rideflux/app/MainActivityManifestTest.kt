/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app

import android.app.Application
import android.content.ComponentName
import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins the manifest entry that keeps [MainActivity] alive when a Bluetooth input device (earbuds
 * with media keys, the ring remote, a keyboard) connects or drops.
 *
 * Those events change the keyboard and navigation configuration. An activity that does not
 * declare them is destroyed and recreated each time, which also throws away composition state
 * such as the pending action of an open permission prompt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MainActivityManifestTest {

    private val declared: Int
        get() {
            val context = RuntimeEnvironment.getApplication()
            return context.packageManager
                .getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
                .configChanges
        }

    @Test
    fun handlesKeyboardAndNavigationChangesItself() {
        val expected = ActivityInfo.CONFIG_KEYBOARD or
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
            ActivityInfo.CONFIG_NAVIGATION
        assertEquals(expected, declared and expected)
    }

    @Test
    fun stillRecreatesForChangesTheLayoutDependsOn() {
        // Orientation, size, density, locale, night mode and font scale change what is drawn and
        // keep recreating the activity as before; only the input-device flags are handled in place.
        val layoutDependent = ActivityInfo.CONFIG_ORIENTATION or
            ActivityInfo.CONFIG_SCREEN_SIZE or
            ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or
            ActivityInfo.CONFIG_DENSITY or
            ActivityInfo.CONFIG_LOCALE or
            ActivityInfo.CONFIG_UI_MODE or
            ActivityInfo.CONFIG_FONT_SCALE
        assertTrue(
            "MainActivity must not swallow layout-dependent configuration changes",
            (declared and layoutDependent) == 0,
        )
    }
}
