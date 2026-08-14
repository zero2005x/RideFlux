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

internal object GlassesLinkPreferences {
    private const val PREFS = "rideflux_bridge"
    private const val KEY_MODE = "glasses_link_mode"

    fun read(context: Context): GlassesLinkMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
        return runCatching { GlassesLinkMode.valueOf(raw.orEmpty()) }
            .getOrDefault(GlassesLinkMode.ANDROID_BLE)
    }

    fun write(context: Context, mode: GlassesLinkMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }
}
