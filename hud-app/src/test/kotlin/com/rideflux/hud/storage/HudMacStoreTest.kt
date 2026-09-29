/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.storage

import android.content.Context
import com.rideflux.data.bridge.BridgePairingToken
import com.rideflux.domain.wheel.WheelFamily
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HudMacStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("hud_target", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun targetAndPairingPersistAcrossStoreInstances() {
        val store = HudMacStore(context)
        assertNull(store.readMac())
        assertEquals(HudMacStore.DEFAULT_FAMILY, store.readFamily())
        assertNull(store.readPairedPhoneMac())
        assertNull(store.readPairedPhoneToken())

        store.write("AA:BB:CC:DD:EE:FF", WheelFamily.K)
        store.writePairedPhoneMac(" 11:22:33:44:55:66 ")
        store.writePairedPhoneToken("0102030405060708")
        val glassesToken = store.readOrCreateGlassesToken()

        val restarted = HudMacStore(context)
        assertEquals("AA:BB:CC:DD:EE:FF", restarted.readMac())
        assertEquals(WheelFamily.K, restarted.readFamily())
        assertEquals("11:22:33:44:55:66", restarted.readPairedPhoneMac())
        assertArrayEquals(BridgePairingToken.fromHex("0102030405060708"), restarted.readPairedPhoneToken())
        assertArrayEquals(glassesToken, restarted.readOrCreateGlassesToken())
    }

    @Test
    fun rejectsMalformedPairingAndFallsBackFromCorruptFamily() {
        val store = HudMacStore(context)
        assertThrows(IllegalArgumentException::class.java) {
            store.writePairedPhoneMac("invalid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.writePairedPhoneToken("bad token")
        }
        assertNull(store.readPairedPhoneToken())

        context.getSharedPreferences("hud_target", Context.MODE_PRIVATE).edit()
            .putString("last_family", "unrecognized")
            .putString("glasses_token", "invalid")
            .commit()
        assertEquals(HudMacStore.DEFAULT_FAMILY, store.readFamily())
        assertTrue(store.readOrCreateGlassesToken().size == 8)
    }
}
