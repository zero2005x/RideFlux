/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the transport the bridge resolves with the service stopped —
 * the state in which the scanner screen reads it, and the one that
 * silently disagreed with the stored preference before the migration
 * existed.
 */
class GlassesLinkModeTest {

    @Test
    fun freshInstall_defaultsToNativeBle() {
        val resolved = resolveStoredLinkMode(raw = null, migrated = false)

        assertEquals(GlassesLinkMode.ANDROID_BLE, resolved.mode)
        assertFalse("nothing was stored, so nothing needs writing back", resolved.persist)
    }

    @Test
    fun unparseableValue_fallsBackToNativeBleWithoutRewriting() {
        val resolved = resolveStoredLinkMode(raw = "ROKID_CXR_V2", migrated = false)

        assertEquals(GlassesLinkMode.ANDROID_BLE, resolved.mode)
        assertFalse(resolved.persist)
    }

    @Test
    fun storedCxr_migratesToNativeBleOnce() {
        val resolved = resolveStoredLinkMode(raw = "ROKID_CXR", migrated = false)

        assertEquals(GlassesLinkMode.ANDROID_BLE, resolved.mode)
        assertTrue("the migration must be written back so it runs once", resolved.persist)
    }

    @Test
    fun cxrChosenAfterMigration_isRespected() {
        val resolved = resolveStoredLinkMode(raw = "ROKID_CXR", migrated = true)

        assertEquals(
            "a rider who re-selects CXR keeps it; the migration is not a ban",
            GlassesLinkMode.ROKID_CXR,
            resolved.mode,
        )
        assertFalse(resolved.persist)
    }

    @Test
    fun storedNativeBle_isReturnedUnchanged() {
        for (migrated in listOf(false, true)) {
            val resolved = resolveStoredLinkMode(raw = "ANDROID_BLE", migrated = migrated)

            assertEquals(GlassesLinkMode.ANDROID_BLE, resolved.mode)
            assertFalse(resolved.persist)
        }
    }

    @Test
    fun oneFailedCxrOpen_degradesToNativeBle() {
        // open() now waits out a full connection attempt (~20 s), so the
        // first failure is already enough evidence: retrying only leaves
        // the rider with a phone advertising nothing for longer.
        assertFalse(shouldDegradeFromCxr(0))
        assertTrue(shouldDegradeFromCxr(1))
        assertTrue(shouldDegradeFromCxr(2))
    }
}
