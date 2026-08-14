/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.storage

import android.content.Context
import android.bluetooth.BluetoothAdapter
import android.content.SharedPreferences
import android.util.Log
import com.rideflux.domain.wheel.WheelFamily
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Locale

/**
 * Persists the most recently used wheel target (MAC + protocol
 * family) so the HUD APK can be re-launched from the launcher icon
 * without an `--es mac` intent extra and still reconnect.
 *
 * Both fields are written together whenever [HudActivity] is started
 * with explicit extras; subsequent launches without extras read the
 * cached values back. Family defaults to [DEFAULT_FAMILY] when the
 * preference is missing or unparseable so the HUD never crashes on
 * a malformed preference.
 */
@Singleton
class HudMacStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the cached MAC, or `null` when nothing has been saved yet. */
    fun readMac(): String? = prefs.getString(KEY_MAC, null)

    /** Phone allowed to provide native BLE bridge frames. */
    fun readPairedPhoneMac(): String? = prefs.getString(KEY_PAIRED_PHONE_MAC, null)

    fun writePairedPhoneMac(mac: String) {
        val normalised = mac.trim().uppercase(Locale.ROOT)
        require(BluetoothAdapter.checkBluetoothAddress(normalised)) { "Invalid Bluetooth address" }
        if (!prefs.edit().putString(KEY_PAIRED_PHONE_MAC, normalised).commit()) {
            Log.w(TAG, "paired phone commit returned false")
        }
    }

    /** Returns the cached family, falling back to [DEFAULT_FAMILY]. */
    fun readFamily(): WheelFamily =
        prefs.getString(KEY_FAMILY, null)
            ?.let { raw ->
                runCatching { WheelFamily.valueOf(raw) }
                    .onFailure { Log.w(TAG, "Unparseable stored family '$raw'; using default", it) }
                    .getOrDefault(DEFAULT_FAMILY)
            }
            ?: DEFAULT_FAMILY

    /**
     * Persists [mac] and [family] verbatim. No validation of [mac]
     * here (the caller is responsible for blank-checking). Uses
     * [SharedPreferences.Editor.commit] on a background thread so the
     * write is durable before returning — the reconnect target must
     * not be lost if the process dies right after.
     */
    fun write(mac: String, family: WheelFamily) {
        val editor = prefs.edit()
            .putString(KEY_MAC, mac)
            .putString(KEY_FAMILY, family.name)
        try {
            if (!editor.commit()) {
                Log.w(TAG, "commit returned false")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "commit failed", t)
        }
    }

    companion object {
        private const val TAG = "HudMacStore"
        private const val PREFS_NAME = "hud_target"
        private const val KEY_MAC = "last_mac"
        private const val KEY_FAMILY = "last_family"
        private const val KEY_PAIRED_PHONE_MAC = "paired_phone_mac"

        /** Begode / Gotway / ExtremeBull — the most common family. */
        val DEFAULT_FAMILY: WheelFamily = WheelFamily.G
    }
}
