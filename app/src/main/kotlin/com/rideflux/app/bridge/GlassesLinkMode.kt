/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.content.Context

/** Phone-to-glasses transport selected by the rider. */
enum class GlassesLinkMode {
    ANDROID_BLE,
    ROKID_CXR,
}

/** Transport health, independent from the phone-to-wheel state. */
enum class GlassesLinkState {
    STOPPED,
    STARTING,
    READY,
    CONNECTED,
    ERROR,
}

/**
 * Outcome of reading a stored link-mode preference.
 *
 * @param persist true when the resolved mode differs from what is on
 *   disk and must be written back, so the migration runs exactly once.
 */
internal data class ResolvedLinkMode(val mode: GlassesLinkMode, val persist: Boolean)

/**
 * Resolves the stored preference, applying the one-time migration off
 * [GlassesLinkMode.ROKID_CXR].
 *
 * CXR only connects on provisioned hardware; on consumer RV101 units it
 * loops on connection timeouts while the phone advertises nothing at
 * all, leaving the HUD with no transport to find. Installs carrying a
 * stored CXR preference from before that was understood are moved to
 * [GlassesLinkMode.ANDROID_BLE] once. The migration flag is what makes
 * it once — a rider who deliberately re-selects CXR afterwards keeps it.
 *
 * Kept as a pure function so the migration is unit-testable without a
 * [Context].
 */
internal fun resolveStoredLinkMode(raw: String?, migrated: Boolean): ResolvedLinkMode {
    val stored = runCatching { GlassesLinkMode.valueOf(raw.orEmpty()) }.getOrNull()
        ?: return ResolvedLinkMode(GlassesLinkMode.ANDROID_BLE, persist = false)
    if (stored == GlassesLinkMode.ROKID_CXR && !migrated) {
        return ResolvedLinkMode(GlassesLinkMode.ANDROID_BLE, persist = true)
    }
    return ResolvedLinkMode(stored, persist = false)
}

internal object GlassesLinkPreferences {
    private const val KEY_MODE = "glasses_link_mode"
    private const val KEY_MIGRATED_OFF_CXR = "glasses_link_mode_migrated_off_cxr"

    fun read(context: Context): GlassesLinkMode {
        val prefs = context.getSharedPreferences(BRIDGE_PREFS_NAME, Context.MODE_PRIVATE)
        val resolved = resolveStoredLinkMode(
            raw = prefs.getString(KEY_MODE, null),
            migrated = prefs.getBoolean(KEY_MIGRATED_OFF_CXR, false),
        )
        if (resolved.persist) {
            prefs.edit()
                .putString(KEY_MODE, resolved.mode.name)
                .putBoolean(KEY_MIGRATED_OFF_CXR, true)
                .apply()
        }
        return resolved.mode
    }

    fun write(context: Context, mode: GlassesLinkMode) {
        context.getSharedPreferences(BRIDGE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            // An explicit choice settles the migration either way: the
            // rider has now picked a transport, so never rewrite it.
            .putBoolean(KEY_MIGRATED_OFF_CXR, true)
            .apply()
    }
}
