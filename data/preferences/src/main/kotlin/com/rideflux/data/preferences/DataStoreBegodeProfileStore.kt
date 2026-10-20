/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rideflux.domain.wheel.BegodeProfileStore
import com.rideflux.domain.wheel.BegodeProtocolProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

private val Context.rideFluxBegodeProfiles by preferencesDataStore(name = "rideflux_begode_profiles")

/**
 * [BegodeProfileStore] in its own DataStore file, one string preference per wheel
 * (`profile_<ADDRESS>`). Unknown stored names are dropped. [BegodeProtocolProfile.UNKNOWN]
 * removes the preference so a missing entry stays the default.
 */
class DataStoreBegodeProfileStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : BegodeProfileStore {
    constructor(
        context: Context,
        scope: CoroutineScope,
    ) : this(context.applicationContext.rideFluxBegodeProfiles, scope)

    override val profiles: StateFlow<Map<String, BegodeProtocolProfile>> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::toMap)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override suspend fun setProfile(address: String, profile: BegodeProtocolProfile) {
        val key = BegodeProfileStore.key(address)
        require(key.isNotEmpty()) { "address must not be blank" }
        dataStore.edit { preferences ->
            val preferenceKey = stringPreferencesKey(PREFIX + key)
            if (profile == BegodeProtocolProfile.UNKNOWN) {
                preferences.remove(preferenceKey)
            } else {
                preferences[preferenceKey] = profile.name
            }
        }
    }

    /** Names that are not a current profile are dropped rather than guessed. */
    private fun toMap(preferences: Preferences): Map<String, BegodeProtocolProfile> =
        preferences.asMap().mapNotNull { (key, value) ->
            val stored = value as? String ?: return@mapNotNull null
            if (!key.name.startsWith(PREFIX)) return@mapNotNull null
            val profile = BegodeProtocolProfile.entries.firstOrNull { it.name == stored }
                ?: return@mapNotNull null
            if (profile == BegodeProtocolProfile.UNKNOWN) return@mapNotNull null
            key.name.removePrefix(PREFIX) to profile
        }.toMap()

    private companion object {
        const val PREFIX = "profile_"
    }
}
