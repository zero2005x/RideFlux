/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.alert

sealed class ThresholdAlert {
    data class Overspeed(val speedKmh: Float, val limitKmh: Float) : ThresholdAlert()
    data class OverTemperature(val temperatureC: Float, val limitC: Float) : ThresholdAlert()
    data class LowBattery(val percent: Float, val limitPercent: Float) : ThresholdAlert()
    data class PwmLoad(val pwmPercent: Float, val limitPercent: Float) : ThresholdAlert()
}
