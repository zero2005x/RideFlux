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

@RunWith(RobolectricTestRunner::class)
class ApprovedGlassesStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("rideflux_approved_glasses", Context.MODE_PRIVATE)
            .edit().clear().commit()
        resetStore()
    }

    @Test
    fun approvalsPersistAndReplaceMatchingTokenOrMac() {
        val first = ApprovedGlasses("0102030405060708", "AA:BB:CC:DD:EE:FF", "0102", 1L)
        ApprovedGlassesStore.add(context, first)
        assertTrue(ApprovedGlassesStore.isApproved(context, " 0102030405060708 ", null))
        assertTrue(ApprovedGlassesStore.isApproved(context, null, " aa:bb:cc:dd:ee:ff "))
        assertFalse(ApprovedGlassesStore.isApproved(context, "1111111111111111", null))

        val replacement = ApprovedGlasses("0102030405060708", "11:22:33:44:55:66", "new", 2L)
        ApprovedGlassesStore.add(context, replacement)
        assertEquals(listOf(replacement), ApprovedGlassesStore.getAll(context))
        assertFalse(ApprovedGlassesStore.isApproved(context, null, first.mac))

        val legacy = ApprovedGlasses(null, "77:88:99:AA:BB:CC", "BBCC", 3L)
        ApprovedGlassesStore.add(context, legacy)
        assertEquals(listOf(legacy, replacement), ApprovedGlassesStore.itemsFlow.value)
        ApprovedGlassesStore.remove(context, legacy.copy(shortCode = "ignored"))
        assertEquals(listOf(replacement), ApprovedGlassesStore.getAll(context))

        resetStore() // Simulate process restart: reload the durable allowlist.
        assertEquals(listOf(replacement), ApprovedGlassesStore.getAll(context))
        assertTrue(ApprovedGlassesStore.isApproved(context, "0102030405060708", null))
    }

    @Test
    fun loadDerivesDisplayCodesForLegacyRecordsAndHandlesCorruption() {
        val prefs = context.getSharedPreferences("rideflux_approved_glasses", Context.MODE_PRIVATE)
        prefs.edit().putString(
            "approved_items",
            """[{"token":"0102030405060708"},{"mac":"aa:bb:cc:dd:ee:ff"},{}]""",
        ).commit()
        val loaded = ApprovedGlassesStore.getAll(context)
        assertEquals(listOf("0102", "EEFF", "????"), loaded.map { it.shortCode })

        prefs.edit().putString("approved_items", "not JSON").commit()
        resetStore()
        assertTrue(ApprovedGlassesStore.getAll(context).isEmpty())
    }

    private fun resetStore() {
        val initialized = ApprovedGlassesStore::class.java.getDeclaredField("initialized")
        initialized.isAccessible = true
        initialized.setBoolean(null, false)
    }
}
