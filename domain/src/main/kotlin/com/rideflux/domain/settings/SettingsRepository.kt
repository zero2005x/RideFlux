/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.settings

import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    suspend fun current(): AppSettings

    suspend fun setSpeedLimitKmh(value: Float)
    suspend fun setTemperatureLimitC(value: Float)
    suspend fun setLowBatteryPercent(value: Float)
    suspend fun setPwmAlertPercent(value: Float)
    suspend fun setAlertsEnabled(value: Boolean)
    suspend fun setUseMetric(value: Boolean)
    suspend fun setKeepScreenOnDashboard(value: Boolean)
    suspend fun setBridgeAutostart(value: Boolean)
    suspend fun setBridgeStandbyAdvertiseLowLatency(value: Boolean)
    suspend fun setHudPeerMac(value: String?)
}
