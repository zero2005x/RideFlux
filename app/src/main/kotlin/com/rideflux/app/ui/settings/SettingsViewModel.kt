/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rideflux.app.bridge.BridgePairingStore
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings

    private val _pairingCode = MutableStateFlow<String?>(null)

    /**
     * This phone's bridge pairing code, shown so the rider can confirm
     * the glasses paired with the right phone. Resolved off the main
     * thread because reading it mints and commits the token on first
     * run.
     */
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

    init {
        viewModelScope.launch {
            _pairingCode.value = withContext(Dispatchers.IO) {
                BridgePairingStore.displayCode(appContext)
            }
        }
    }

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
