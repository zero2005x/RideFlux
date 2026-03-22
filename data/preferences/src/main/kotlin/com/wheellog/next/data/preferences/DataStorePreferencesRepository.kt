package com.wheellog.next.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wheellog.next.domain.repository.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wheellog_prefs")

/**
 * DataStore-backed implementation of [PreferencesRepository].
 */
@Singleton
class DataStorePreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : PreferencesRepository {

    private object Keys {
        val LAST_DEVICE_ADDRESS = stringPreferencesKey("last_device_address")
        val USE_METRIC_UNITS = booleanPreferencesKey("use_metric_units")
        val OVERSPEED_THRESHOLD_KMH = floatPreferencesKey("overspeed_threshold_kmh")
    }

    override fun lastDeviceAddress(): Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[Keys.LAST_DEVICE_ADDRESS] }

    override suspend fun saveLastDeviceAddress(address: String) {
        context.dataStore.edit { prefs -> prefs[Keys.LAST_DEVICE_ADDRESS] = address }
    }

    override fun useMetricUnits(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[Keys.USE_METRIC_UNITS] ?: true }

    override suspend fun setUseMetricUnits(metric: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.USE_METRIC_UNITS] = metric }
    }

    override fun overspeedThresholdKmh(): Flow<Float> =
        context.dataStore.data.map { prefs -> prefs[Keys.OVERSPEED_THRESHOLD_KMH] ?: 30f }

    override suspend fun setOverspeedThresholdKmh(kmh: Float) {
        context.dataStore.edit { prefs -> prefs[Keys.OVERSPEED_THRESHOLD_KMH] = kmh }
    }
}
