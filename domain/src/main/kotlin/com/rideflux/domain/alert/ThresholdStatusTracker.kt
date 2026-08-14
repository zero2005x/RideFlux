/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.alert

import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.telemetry.WheelTelemetry
import kotlin.math.abs

/** Stateful threshold status for continuously rendered warning surfaces such as the HUD border. */
class ThresholdStatusTracker(
    private val requiredSamples: Int = ThresholdMonitor.DEFAULT_REQUIRED_SAMPLES,
) {
    private data class State(var consecutive: Int = 0, var active: Boolean = false)
    private val speed = State()
    private val temperature = State()
    private val battery = State()
    private val pwm = State()

    fun update(telemetry: WheelTelemetry, thresholds: AlertThresholds): Boolean {
        if (!thresholds.enabled) {
            listOf(speed, temperature, battery, pwm).forEach { it.consecutive = 0; it.active = false }
            return false
        }
        updateHigh(speed, telemetry.speedKmh?.let(::abs), thresholds.speedLimitKmh)
        updateHigh(temperature, telemetry.mosTemperatureC, thresholds.temperatureLimitC)
        updateLow(battery, telemetry.batteryPercent, thresholds.lowBatteryPercent)
        updateHigh(pwm, telemetry.pwmPercent?.let(::abs), thresholds.pwmAlertPercent)
        return speed.active || temperature.active || battery.active || pwm.active
    }

    private fun updateHigh(state: State, value: Float?, limit: Float) {
        if (value == null || !value.isFinite()) { state.consecutive = 0; return }
        if (state.active) {
            if (value < limit * ThresholdMonitor.HYSTERESIS_FACTOR) state.active = false
            return
        }
        updateInactive(state, value > limit)
    }

    private fun updateLow(state: State, value: Float?, limit: Float) {
        if (value == null || !value.isFinite()) { state.consecutive = 0; return }
        if (state.active) {
            if (value > limit / ThresholdMonitor.HYSTERESIS_FACTOR) state.active = false
            return
        }
        updateInactive(state, value < limit)
    }

    private fun updateInactive(state: State, exceeded: Boolean) {
        state.consecutive = if (exceeded) state.consecutive + 1 else 0
        if (state.consecutive >= requiredSamples) {
            state.active = true
            state.consecutive = 0
        }
    }
}
