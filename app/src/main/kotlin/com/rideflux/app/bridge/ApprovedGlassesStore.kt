/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.content.Context
import android.util.Log
import com.rideflux.data.bridge.BridgePairingToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Record of an approved AR glasses device.
 *
 * @param tokenHex 16-character hex representation of the glasses' 8-byte token,
 *   or null if paired from a legacy HUD without handshake token support.
 * @param mac Bluetooth MAC address of the glasses.
 * @param shortCode 4-character display code, e.g. "A1B2".
 * @param addedAtMillis Timestamp when the device was approved.
 */
data class ApprovedGlasses(
    val tokenHex: String?,
    val mac: String?,
    val shortCode: String,
    val addedAtMillis: Long = System.currentTimeMillis(),
) {
    val isLegacy: Boolean get() = tokenHex == null
}

/**
 * Persists the allowlist of user-approved AR glasses in [android.content.SharedPreferences].
 *
 * Unlike the previous Bluetooth bond check, this does not rely on system
 * pairing (which BLE GATT does not require). Centrals presenting an approved
 * token or MAC are permitted to subscribe silently; unknown centrals require
 * explicit user approval.
 */
internal object ApprovedGlassesStore {
    private const val TAG = "ApprovedGlassesStore"
    private const val PREFS_NAME = "rideflux_approved_glasses"
    private const val KEY_ITEMS = "approved_items"

    private val _itemsFlow = MutableStateFlow<List<ApprovedGlasses>>(emptyList())
    val itemsFlow: StateFlow<List<ApprovedGlasses>> = _itemsFlow.asStateFlow()

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    _itemsFlow.value = load(context)
                    initialized = true
                }
            }
        }
    }

    fun getAll(context: Context): List<ApprovedGlasses> {
        init(context)
        return _itemsFlow.value
    }

    fun isApproved(context: Context, tokenHex: String?, mac: String?): Boolean {
        init(context)
        val current = _itemsFlow.value
        if (tokenHex != null) {
            val normToken = tokenHex.trim()
            if (current.any { it.tokenHex?.equals(normToken, ignoreCase = true) == true }) {
                return true
            }
        }
        if (mac != null) {
            val normMac = mac.trim().uppercase(Locale.ROOT)
            if (current.any { it.mac?.uppercase(Locale.ROOT) == normMac }) {
                return true
            }
        }
        return false
    }

    fun add(context: Context, glasses: ApprovedGlasses) {
        synchronized(this) {
            init(context)
            val current = _itemsFlow.value.toMutableList()
            current.removeAll { existing ->
                (glasses.tokenHex != null && existing.tokenHex?.equals(glasses.tokenHex, ignoreCase = true) == true) ||
                    (glasses.mac != null && existing.mac?.equals(glasses.mac, ignoreCase = true) == true)
            }
            current.add(0, glasses)
            save(context, current)
            _itemsFlow.value = current
            Log.i(TAG, "added approved glasses: ${glasses.shortCode} token=${glasses.tokenHex} mac=${glasses.mac}")
        }
    }

    fun remove(context: Context, glasses: ApprovedGlasses) {
        synchronized(this) {
            init(context)
            val current = _itemsFlow.value.toMutableList()
            current.removeAll { existing ->
                (glasses.tokenHex != null && existing.tokenHex?.equals(glasses.tokenHex, ignoreCase = true) == true) ||
                    (glasses.mac != null && existing.mac?.equals(glasses.mac, ignoreCase = true) == true)
            }
            save(context, current)
            _itemsFlow.value = current
            Log.i(TAG, "removed approved glasses: ${glasses.shortCode}")
        }
    }

    private fun load(context: Context): List<ApprovedGlasses> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        val list = mutableListOf<ApprovedGlasses>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val token = obj.optString("token").takeIf { it.isNotBlank() }
                val mac = obj.optString("mac").takeIf { it.isNotBlank() }
                val shortCode = obj.optString("shortCode").takeIf { it.isNotBlank() }
                    ?: token?.let(BridgePairingToken::shortCodeOfHex)
                    ?: mac?.replace(":", "")?.takeLast(4)?.uppercase(Locale.ROOT)
                    ?: "????"
                val addedAt = obj.optLong("addedAt", System.currentTimeMillis())
                list.add(ApprovedGlasses(token, mac, shortCode, addedAt))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to parse approved glasses JSON", t)
        }
        return list
    }

    private fun save(context: Context, items: List<ApprovedGlasses>) {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("token", item.tokenHex.orEmpty())
                put("mac", item.mac.orEmpty())
                put("shortCode", item.shortCode)
                put("addedAt", item.addedAtMillis)
            }
            arr.put(obj)
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.edit().putString(KEY_ITEMS, arr.toString()).commit()) {
            Log.w(TAG, "save approved glasses commit returned false")
        }
    }
}
