/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app

import android.app.Application
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import com.rideflux.app.ui.bond.PendingBondImport
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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

    @Test
    fun registersIntentFiltersForRfbondFiles() {
        val context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager

        // ACTION_VIEW with content scheme
        val viewContentIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://com.example.provider/backup.rfbond"), "application/octet-stream")
        }
        val viewMatches = pm.queryIntentActivities(viewContentIntent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(
            "MainActivity must resolve ACTION_VIEW for .rfbond files",
            viewMatches.any { it.activityInfo.name == MainActivity::class.java.name },
        )

        // ACTION_SEND
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
        }
        val sendMatches = pm.queryIntentActivities(sendIntent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(
            "MainActivity must resolve ACTION_SEND for application/octet-stream",
            sendMatches.any { it.activityInfo.name == MainActivity::class.java.name },
        )
    }

    @Test
    fun extractBondUriExtractsCorrectUris() {
        // VIEW
        val viewUri = Uri.parse("content://media/backup.rfbond")
        val viewIntent = Intent(Intent.ACTION_VIEW).apply { data = viewUri }
        assertEquals(viewUri, MainActivity.extractBondUri(viewIntent))

        // SEND with EXTRA_STREAM
        val sendUri = Uri.parse("content://media/shared.rfbond")
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, sendUri)
        }
        assertEquals(sendUri, MainActivity.extractBondUri(sendIntent))

        // SEND with ClipData fallback
        val clipUri = Uri.parse("content://media/clip.rfbond")
        val clipIntent = Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newRawUri("bond", clipUri)
        }
        assertEquals(clipUri, MainActivity.extractBondUri(clipIntent))

        // Non-bond actions return null
        val mainIntent = Intent(Intent.ACTION_MAIN)
        assertNull(MainActivity.extractBondUri(mainIntent))

        // Null intent returns null
        assertNull(MainActivity.extractBondUri(null))
    }

    @Test
    fun handleBondIntentUpdatesPendingBondUri() {
        PendingBondImport.pendingUri.value = null
        assertFalse(MainActivity.handleBondIntent(null))
        assertFalse(MainActivity.handleBondIntent(Intent(Intent.ACTION_MAIN)))

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://media/backup.rfbond")
        }
        assertTrue(MainActivity.handleBondIntent(viewIntent))
        assertEquals("content://media/backup.rfbond", PendingBondImport.pendingUri.value)
    }

    @Test
    fun onNewIntentHandlesBondIntent() {
        PendingBondImport.pendingUri.value = null
        val activity = MainActivity()
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/shared.rfbond"))
        }
        activity.onNewIntent(sendIntent)
        assertEquals("content://media/shared.rfbond", PendingBondImport.pendingUri.value)
    }
}
