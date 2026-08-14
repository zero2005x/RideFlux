/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

private val Context.rideFluxSettings by preferencesDataStore(name = "rideflux_settings")

class DataStoreSettingsRepository(
    context: Context,
    scope: CoroutineScope,
) : SettingsRepository {
    private val dataStore = context.applicationContext.rideFluxSettings

    override val settings: StateFlow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map(::toSettings)
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    override suspend fun current(): AppSettings = toSettings(
        dataStore.data.catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }.first(),
    )

    override suspend fun setSpeedLimitKmh(value: Float) = setFloat(Keys.SPEED_LIMIT, value, 1f..150f)
    override suspend fun setTemperatureLimitC(value: Float) = setFloat(Keys.TEMP_LIMIT, value, 20f..150f)
    override suspend fun setLowBatteryPercent(value: Float) = setFloat(Keys.LOW_BATTERY, value, 1f..99f)
    override suspend fun setPwmAlertPercent(value: Float) = setFloat(Keys.PWM_ALERT, value, 1f..100f)
    override suspend fun setAlertsEnabled(value: Boolean) = setBoolean(Keys.ALERTS_ENABLED, value)
    override suspend fun setUseMetric(value: Boolean) = setBoolean(Keys.USE_METRIC, value)
    override suspend fun setKeepScreenOnDashboard(value: Boolean) = setBoolean(Keys.KEEP_SCREEN_ON, value)
    override suspend fun setBridgeAutostart(value: Boolean) = setBoolean(Keys.BRIDGE_AUTOSTART, value)
    override suspend fun setBridgeStandbyAdvertiseLowLatency(value: Boolean) =
        setBoolean(Keys.BRIDGE_STANDBY_LOW_LATENCY, value)

    override suspend fun setHudPeerMac(value: String?) {
        dataStore.edit { preferences ->
            val normalised = value?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
            if (normalised == null) preferences.remove(Keys.HUD_PEER_MAC)
            else preferences[Keys.HUD_PEER_MAC] = normalised
        }
    }

    private suspend fun setFloat(key: Preferences.Key<Float>, value: Float, range: ClosedFloatingPointRange<Float>) {
        require(value.isFinite() && value in range) { "$value is outside $range" }
        dataStore.edit { it[key] = value }
    }

    private suspend fun setBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    private fun toSettings(p: Preferences) = AppSettings(
        alertThresholds = AlertThresholds(
            speedLimitKmh = p[Keys.SPEED_LIMIT] ?: 45f,
            temperatureLimitC = p[Keys.TEMP_LIMIT] ?: 80f,
            lowBatteryPercent = p[Keys.LOW_BATTERY] ?: 25f,
            pwmAlertPercent = p[Keys.PWM_ALERT] ?: 90f,
            enabled = p[Keys.ALERTS_ENABLED] ?: true,
        ),
        useMetric = p[Keys.USE_METRIC] ?: true,
        keepScreenOnDashboard = p[Keys.KEEP_SCREEN_ON] ?: true,
        bridgeAutostart = p[Keys.BRIDGE_AUTOSTART] ?: true,
        bridgeStandbyAdvertiseLowLatency = p[Keys.BRIDGE_STANDBY_LOW_LATENCY] ?: false,
        hudPeerMac = p[Keys.HUD_PEER_MAC],
    )

    private object Keys {
        val SPEED_LIMIT = floatPreferencesKey("speed_limit_kmh")
        val TEMP_LIMIT = floatPreferencesKey("temp_limit_c")
        val LOW_BATTERY = floatPreferencesKey("low_battery_percent")
        val PWM_ALERT = floatPreferencesKey("pwm_alert_percent")
        val ALERTS_ENABLED = booleanPreferencesKey("alerts_enabled")
        val USE_METRIC = booleanPreferencesKey("use_metric")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on_dashboard")
        val BRIDGE_AUTOSTART = booleanPreferencesKey("bridge_autostart")
        val BRIDGE_STANDBY_LOW_LATENCY = booleanPreferencesKey("bridge_standby_advertise_low_latency")
        val HUD_PEER_MAC = stringPreferencesKey("hud_peer_mac")
    }
}
