/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings

    fun setSpeedLimit(value: Float) = update { repository.setSpeedLimitKmh(value) }
    fun setTemperatureLimit(value: Float) = update { repository.setTemperatureLimitC(value) }
    fun setLowBattery(value: Float) = update { repository.setLowBatteryPercent(value) }
    fun setPwmAlert(value: Float) = update { repository.setPwmAlertPercent(value) }
    fun setAlertsEnabled(value: Boolean) = update { repository.setAlertsEnabled(value) }
    fun setUseMetric(value: Boolean) = update { repository.setUseMetric(value) }
    fun setKeepScreenOn(value: Boolean) = update { repository.setKeepScreenOnDashboard(value) }
    fun setBridgeAutostart(value: Boolean) = update { repository.setBridgeAutostart(value) }
    fun setStandbyLowLatency(value: Boolean) = update { repository.setBridgeStandbyAdvertiseLowLatency(value) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
