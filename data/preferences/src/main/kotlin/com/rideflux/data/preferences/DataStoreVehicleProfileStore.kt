/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
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
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.garage.VehicleCalibration
import com.rideflux.domain.garage.VehicleProfile
import com.rideflux.domain.garage.VehicleProfileStore
import com.rideflux.domain.garage.normalizeVehicleAddress
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentityEvidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

private val Context.rideFluxVehicles by preferencesDataStore(name = "rideflux_vehicles")

/**
 * [VehicleProfileStore] persisted in its own DataStore file.
 *
 * This is deliberately separate from [DataStoreSettingsRepository]: display
 * preferences (`useMetric`, screen-on, HUD layout) are global, while a vehicle
 * profile is scoped to one BLE identity. The existing per-address Begode
 * layout and battery-pack stores are kept as-is; this store does not duplicate
 * or replace them.
 *
 * One string preference per vehicle (`vehicle_<NORMALIZED_ADDRESS>`). Records
 * that fail to parse, carry an unknown category/family, or whose id no longer
 * matches the key are dropped rather than guessed — a scooter profile must
 * never silently become a wheel.
 */
class DataStoreVehicleProfileStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : VehicleProfileStore {
    constructor(
        context: Context,
        scope: CoroutineScope,
    ) : this(context.applicationContext.rideFluxVehicles, scope)

    override val profiles: StateFlow<List<VehicleProfile>> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::decodeAll)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val familyHints: StateFlow<Map<String, WheelFamily>> = profiles
        .map { list -> list.mapNotNull { p -> p.family?.let { p.address to it } }.toMap() }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override suspend fun current(): List<VehicleProfile> =
        dataStore.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .map(::decodeAll)
            .first()

    override suspend fun profile(address: String): VehicleProfile? {
        val key = normalizeVehicleAddress(address)
        return current().firstOrNull { it.address == key }
    }

    override suspend fun upsert(profile: VehicleProfile) {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey(PREFIX + profile.id)] = encode(profile)
        }
    }

    override suspend fun update(
        address: String,
        transform: (VehicleProfile) -> VehicleProfile,
    ): VehicleProfile? {
        val key = normalizeVehicleAddress(address)
        val preferenceKey = stringPreferencesKey(PREFIX + key)
        var updated: VehicleProfile? = null
        dataStore.edit { preferences ->
            val stored = preferences[preferenceKey] ?: return@edit
            val current = decode(stored)?.takeIf { it.address == key } ?: return@edit
            val next = transform(current)
            preferences[preferenceKey] = encode(next)
            updated = next
        }
        return updated
    }

    override suspend fun remove(address: String) {
        val key = normalizeVehicleAddress(address)
        dataStore.edit { preferences ->
            preferences.remove(stringPreferencesKey(PREFIX + key))
        }
    }

    private fun decodeAll(preferences: Preferences): List<VehicleProfile> =
        preferences.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(PREFIX)) return@mapNotNull null
            val stored = value as? String ?: return@mapNotNull null
            val expectedAddress = key.name.removePrefix(PREFIX)
            decode(stored)?.takeIf { it.address == expectedAddress }?.takeIf {
                it.id == normalizeVehicleAddress(expectedAddress)
            }
        }.sortedBy { it.displayName }

    private fun encode(profile: VehicleProfile): String = listOf(
        VERSION_V2,
        profile.id,
        profile.address,
        profile.category.name,
        profile.family?.name.orEmpty(),
        escape(profile.displayName),
        escape(profile.modelName.orEmpty()),
        escape(profile.firmwareVersion.orEmpty()),
        profile.alertThresholds?.speedLimitKmh?.toString().orEmpty(),
        profile.alertThresholds?.temperatureLimitC?.toString().orEmpty(),
        profile.alertThresholds?.lowBatteryPercent?.toString().orEmpty(),
        profile.alertThresholds?.pwmAlertPercent?.toString().orEmpty(),
        profile.alertThresholds?.enabled?.toString().orEmpty(),
        profile.calibration.pitchOffsetDegrees.toString(),
        profile.calibration.rollOffsetDegrees.toString(),
        escape(profile.notes.orEmpty()),
        profile.identityEvidence?.name.orEmpty(),
        escape(profile.transportHint.orEmpty()),
    ).joinToString(FIELD_SEPARATOR.toString())

    private fun decode(raw: String): VehicleProfile? {
        val fields = split(raw)
        val version = fields.firstOrNull()
        val expectedCount = when (version) {
            VERSION_V1 -> FIELD_COUNT_V1
            VERSION_V2 -> FIELD_COUNT_V2
            else -> return null
        }
        if (fields.size != expectedCount) return null
        val id = fields[1].trim()
        val address = normalizeVehicleAddress(fields[2])
        if (id.isBlank() || address.isBlank() || id != address) return null
        val category = runCatching { PlevCategory.valueOf(fields[3]) }.getOrNull() ?: return null
        val family = fields[4].takeIf { it.isNotBlank() }?.let { name ->
            runCatching { WheelFamily.valueOf(name) }.getOrNull() ?: return null
        }
        val displayName = unescape(fields[5]).trim().ifBlank { address }
        val identityEvidence = if (version == VERSION_V2) {
            fields[16].takeIf { it.isNotBlank() }?.let { name ->
                runCatching { WheelIdentityEvidence.valueOf(name) }.getOrNull() ?: return null
            }
        } else null
        val transportHint = if (version == VERSION_V2) unescape(fields[17]).takeIf { it.isNotBlank() } else null
        val thresholds = decodeThresholds(fields)
        val calibration = runCatching {
            VehicleCalibration(
                pitchOffsetDegrees = fields[13].toFloat(),
                rollOffsetDegrees = fields[14].toFloat(),
            )
        }.getOrDefault(VehicleCalibration())
        return runCatching {
            VehicleProfile(
                id = id,
                address = address,
                category = category,
                family = family,
                displayName = displayName,
                modelName = unescape(fields[6]).takeIf { it.isNotBlank() },
                firmwareVersion = unescape(fields[7]).takeIf { it.isNotBlank() },
                identityEvidence = identityEvidence,
                transportHint = transportHint,
                alertThresholds = thresholds,
                calibration = calibration,
                notes = unescape(fields[15]).takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    private fun decodeThresholds(fields: List<String>): AlertThresholds? {
        if (fields[8].isBlank() && fields[9].isBlank() && fields[10].isBlank() &&
            fields[11].isBlank() && fields[12].isBlank()
        ) {
            return null
        }
        val speed = fields[8].toFloatOrNull() ?: return null
        val temperature = fields[9].toFloatOrNull() ?: return null
        val lowBattery = fields[10].toFloatOrNull() ?: return null
        val pwm = fields[11].toFloatOrNull() ?: return null
        val enabled = fields[12].toBooleanStrictOrNull() ?: return null
        if (speed !in 1f..150f || temperature !in 20f..150f ||
            lowBattery !in 1f..99f || pwm !in 1f..100f
        ) {
            return null
        }
        return AlertThresholds(
            speedLimitKmh = speed,
            temperatureLimitC = temperature,
            lowBatteryPercent = lowBattery,
            pwmAlertPercent = pwm,
            enabled = enabled,
        )
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("$FIELD_SEPARATOR", "\\$FIELD_SEPARATOR")
            .replace("\n", "\\n")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var escaped = false
        for (c in value) {
            if (escaped) {
                out.append(if (c == 'n') '\n' else c)
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }

    private fun split(raw: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (c in raw) {
            when {
                escaped -> {
                    current.append('\\').append(c)
                    escaped = false
                }
                c == '\\' -> escaped = true
                c == FIELD_SEPARATOR -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        if (escaped) current.append('\\')
        fields += current.toString()
        return fields
    }

    private companion object {
        const val VERSION_V1 = "v1"
        const val VERSION_V2 = "v2"
        const val PREFIX = "vehicle_"
        const val FIELD_SEPARATOR = '|'
        const val FIELD_COUNT_V1 = 16
        const val FIELD_COUNT_V2 = 18
    }
}
