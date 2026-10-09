/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Covers the [Context]-bound half of the link-mode preference: the pure
 * resolver is tested in [GlassesLinkModeTest], but the read/write paths —
 * including the one-time write-back of the CXR migration — had no test,
 * which is why those lines were the uncovered new code in T01.
 */
@RunWith(RobolectricTestRunner::class)
class GlassesLinkPreferencesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences(BRIDGE_PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun readMigratesStoredCxrAndWritesTheFlagBack() {
        prefs().edit()
            .putString("glasses_link_mode", GlassesLinkMode.ROKID_CXR.name)
            .putBoolean("glasses_link_mode_migrated_off_cxr", false)
            .commit()

        assertEquals(GlassesLinkMode.ANDROID_BLE, GlassesLinkPreferences.read(context))

        assertEquals(
            "the resolved mode is written back",
            GlassesLinkMode.ANDROID_BLE.name,
            prefs().getString("glasses_link_mode", null),
        )
        assertTrue(
            "the migration flag is what makes the migration run exactly once",
            prefs().getBoolean("glasses_link_mode_migrated_off_cxr", false),
        )
        // Second read sees the migrated state and must not rewrite again.
        assertEquals(GlassesLinkMode.ANDROID_BLE, GlassesLinkPreferences.read(context))
    }

    @Test
    fun readOfAnEmptyStoreReturnsNativeBleWithoutWriting() {
        assertEquals(GlassesLinkMode.ANDROID_BLE, GlassesLinkPreferences.read(context))

        assertFalse(
            "nothing was stored, so nothing may be written",
            prefs().contains("glasses_link_mode"),
        )
    }

    @Test
    fun writePersistsTheChoiceAndSettlesTheMigrationFlag() {
        GlassesLinkPreferences.write(context, GlassesLinkMode.ROKID_CXR)

        assertEquals(
            GlassesLinkMode.ROKID_CXR.name,
            prefs().getString("glasses_link_mode", null),
        )
        assertTrue(
            "an explicit choice settles the migration either way",
            prefs().getBoolean("glasses_link_mode_migrated_off_cxr", false),
        )
        // A rider who deliberately re-selects CXR keeps it.
        assertEquals(GlassesLinkMode.ROKID_CXR, GlassesLinkPreferences.read(context))
    }
}
