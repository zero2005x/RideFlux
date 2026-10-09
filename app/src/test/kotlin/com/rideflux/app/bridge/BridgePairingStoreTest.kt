/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.content.Context
import com.rideflux.data.bridge.BridgePairingToken
import com.rideflux.data.bridge.BridgeProtocol
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Covers the persistent identity the glasses match on. The store had no
 * test before, which is why its (touched) lines were the only uncovered
 * new code in the T01 Sonar cleanup.
 */
@RunWith(RobolectricTestRunner::class)
class BridgePairingStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences(BRIDGE_PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun mintsAPersistentTokenAndReusesIt() {
        val minted = BridgePairingStore.readOrCreate(context)

        assertEquals(BridgeProtocol.PAIRING_TOKEN_SIZE, minted.size)
        val stored = prefs().getString("pairing_token", null)
        assertNotNull("the minted token must be persisted synchronously", stored)
        assertEquals(BridgePairingToken.toHex(minted), stored)

        // A second call must return the stored token, not mint a new one:
        // glasses already paired to the first token would see a stranger.
        assertArrayEquals(minted, BridgePairingStore.readOrCreate(context))
        assertEquals(BridgePairingToken.displayCode(minted), BridgePairingStore.displayCode(context))
    }

    @Test
    fun reusesATokenStoredByAnEarlierRun() {
        val existing = ByteArray(BridgeProtocol.PAIRING_TOKEN_SIZE) { (it + 1).toByte() }
        prefs().edit().putString("pairing_token", BridgePairingToken.toHex(existing)).commit()

        assertArrayEquals(existing, BridgePairingStore.readOrCreate(context))
        assertEquals(BridgePairingToken.displayCode(existing), BridgePairingStore.displayCode(context))
    }

    @Test
    fun aCorruptStoredValueIsReplacedRatherThanTrusted() {
        prefs().edit().putString("pairing_token", "not-a-token").commit()

        val minted = BridgePairingStore.readOrCreate(context)

        assertEquals(BridgeProtocol.PAIRING_TOKEN_SIZE, minted.size)
        assertEquals(BridgePairingToken.toHex(minted), prefs().getString("pairing_token", null))
    }
}
