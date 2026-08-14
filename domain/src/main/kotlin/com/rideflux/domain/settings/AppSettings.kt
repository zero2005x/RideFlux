/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.settings

data class AlertThresholds(
    val speedLimitKmh: Float = 45f,
    val temperatureLimitC: Float = 80f,
    val lowBatteryPercent: Float = 25f,
    val pwmAlertPercent: Float = 90f,
    val enabled: Boolean = true,
)

data class AppSettings(
    val alertThresholds: AlertThresholds = AlertThresholds(),
    val useMetric: Boolean = true,
    val keepScreenOnDashboard: Boolean = true,
    val bridgeAutostart: Boolean = true,
    val bridgeStandbyAdvertiseLowLatency: Boolean = false,
    val hudPeerMac: String? = null,
)
