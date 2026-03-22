package com.wheellog.next.core.testing

import com.wheellog.next.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake [PreferencesRepository] for unit testing.
 */
class FakePreferencesRepository : PreferencesRepository {

    private val _lastDeviceAddress = MutableStateFlow<String?>(null)
    private val _useMetricUnits = MutableStateFlow(true)
    private val _overspeedThresholdKmh = MutableStateFlow(30f)

    override fun lastDeviceAddress(): Flow<String?> = _lastDeviceAddress
    override suspend fun saveLastDeviceAddress(address: String) {
        _lastDeviceAddress.value = address
    }

    override fun useMetricUnits(): Flow<Boolean> = _useMetricUnits
    override suspend fun setUseMetricUnits(metric: Boolean) {
        _useMetricUnits.value = metric
    }

    override fun overspeedThresholdKmh(): Flow<Float> = _overspeedThresholdKmh
    override suspend fun setOverspeedThresholdKmh(kmh: Float) {
        _overspeedThresholdKmh.value = kmh
    }
}
