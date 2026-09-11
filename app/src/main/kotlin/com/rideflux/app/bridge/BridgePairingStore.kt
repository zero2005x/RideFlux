/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.content.Context
import android.util.Log
import com.rideflux.data.bridge.BridgePairingToken

/** SharedPreferences file shared by every phone-side bridge preference. */
internal const val BRIDGE_PREFS_NAME = "rideflux_bridge"

/**
 * Owns this phone's bridge pairing token — the identity the glasses
 * match on instead of a BLE MAC address, which Android rotates.
 *
 * The token is minted once on first use and then never changes, so it
 * must survive process death before it is advertised: writes use
 * `commit()` rather than `apply()`. A phone that advertised a token,
 * died and came back with a different one would look like a stranger to
 * an already-paired pair of glasses.
 */
internal object BridgePairingStore {

    private const val TAG = "BridgePairingStore"
    private const val KEY_TOKEN = "pairing_token"

    /**
     * This phone's token, minting and persisting one on first call.
     * Safe to call from any thread.
     */
    @Synchronized
    fun readOrCreate(context: Context): ByteArray {
        val prefs = context.applicationContext
            .getSharedPreferences(BRIDGE_PREFS_NAME, Context.MODE_PRIVATE)
        BridgePairingToken.fromHex(prefs.getString(KEY_TOKEN, null))?.let { return it }

        val minted = BridgePairingToken.generate()
        if (!prefs.edit().putString(KEY_TOKEN, BridgePairingToken.toHex(minted)).commit()) {
            // The token still works for this process, but the glasses
            // would have to re-pair after a restart. Worth a loud log.
            Log.w(TAG, "pairing token commit returned false; pairing may not survive a restart")
        } else {
            Log.i(TAG, "minted bridge pairing token ${BridgePairingToken.shortCode(minted)}…")
        }
        return minted
    }

    /** Human-readable form of [readOrCreate], for the settings screen. */
    fun displayCode(context: Context): String =
        BridgePairingToken.displayCode(readOrCreate(context))
}
