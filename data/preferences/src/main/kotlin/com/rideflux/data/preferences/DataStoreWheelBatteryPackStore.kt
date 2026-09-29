/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rideflux.domain.wheel.WheelBatteryPackStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

private val Context.rideFluxWheelPacks by preferencesDataStore(name = "rideflux_wheel_battery_packs")

/**
 * [WheelBatteryPackStore] persisted in its own DataStore file, one integer preference per
 * wheel (`cells_<ADDRESS>`). Kept apart from [DataStoreSettingsRepository] because it is
 * per-wheel rather than app-wide, and so that adding it does not touch the settings model
 * that backups and every other consumer depend on.
 */
class DataStoreWheelBatteryPackStore(
    context: Context,
    scope: CoroutineScope,
) : WheelBatteryPackStore {
    private val dataStore = context.applicationContext.rideFluxWheelPacks

    override val seriesCells: StateFlow<Map<String, Int>> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::toMap)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override suspend fun setSeriesCells(address: String, cells: Int?) {
        val key = WheelBatteryPackStore.key(address)
        require(key.isNotEmpty()) { "address must not be blank" }
        require(cells == null || cells in WheelBatteryPackStore.SUPPORTED_SERIES_CELLS) {
            "$cells cells is not one of ${WheelBatteryPackStore.SUPPORTED_SERIES_CELLS}"
        }
        dataStore.edit { preferences ->
            val preferenceKey = intPreferencesKey(PREFIX + key)
            if (cells == null) preferences.remove(preferenceKey) else preferences[preferenceKey] = cells
        }
    }

    /** Stored values outside the supported sizes are dropped rather than trusted. */
    private fun toMap(preferences: Preferences): Map<String, Int> =
        preferences.asMap().mapNotNull { (key, value) ->
            val cells = value as? Int ?: return@mapNotNull null
            if (!key.name.startsWith(PREFIX) || cells !in WheelBatteryPackStore.SUPPORTED_SERIES_CELLS) {
                return@mapNotNull null
            }
            key.name.removePrefix(PREFIX) to cells
        }.toMap()

    private companion object {
        const val PREFIX = "cells_"
    }
}
