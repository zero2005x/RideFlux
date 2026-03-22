package com.wheellog.next.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * User preferences repository — wraps DataStore.
 * Implemented by :data:preferences.
 */
interface PreferencesRepository {
    /** Last connected device MAC address. */
    fun lastDeviceAddress(): Flow<String?>

    /** Save last connected device MAC. */
    suspend fun saveLastDeviceAddress(address: String)

    /** Speed unit preference (true = km/h, false = mph). */
    fun useMetricUnits(): Flow<Boolean>

    /** Save speed unit preference. */
    suspend fun setUseMetricUnits(metric: Boolean)

    /** Overspeed alert threshold in km/h. */
    fun overspeedThresholdKmh(): Flow<Float>

    /** Save overspeed threshold. */
    suspend fun setOverspeedThresholdKmh(kmh: Float)
}
