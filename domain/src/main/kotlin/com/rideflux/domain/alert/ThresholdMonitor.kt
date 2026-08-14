/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.alert

import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.telemetry.WheelTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Phone/HUD-side threshold evaluator with debounce, cooldown, and hysteresis. */
class ThresholdMonitor(
    telemetry: StateFlow<WheelTelemetry>,
    thresholds: StateFlow<AlertThresholds>,
    scope: CoroutineScope,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val requiredSamples: Int = DEFAULT_REQUIRED_SAMPLES,
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
) {
    private enum class Kind { SPEED, TEMPERATURE, BATTERY, PWM }
    private data class Gate(var consecutive: Int = 0, var armed: Boolean = true, var lastAt: Long = Long.MIN_VALUE)

    private val gates = Kind.entries.associateWith { Gate() }.toMutableMap()
    private val _alerts = MutableSharedFlow<ThresholdAlert>(extraBufferCapacity = 8)
    val alerts: SharedFlow<ThresholdAlert> = _alerts.asSharedFlow()

    private val collectionJob: Job = scope.launch {
        telemetry.collect { sample -> evaluate(sample, thresholds.value) }
    }

    fun cancel() = collectionJob.cancel()

    private suspend fun evaluate(sample: WheelTelemetry, limits: AlertThresholds) {
        if (!limits.enabled) {
            gates.values.forEach { it.consecutive = 0; it.armed = true }
            return
        }
        checkHigh(Kind.SPEED, sample.speedKmh?.let(::abs), limits.speedLimitKmh) {
            ThresholdAlert.Overspeed(it, limits.speedLimitKmh)
        }
        checkHigh(Kind.TEMPERATURE, sample.mosTemperatureC, limits.temperatureLimitC) {
            ThresholdAlert.OverTemperature(it, limits.temperatureLimitC)
        }
        checkLow(Kind.BATTERY, sample.batteryPercent, limits.lowBatteryPercent) {
            ThresholdAlert.LowBattery(it, limits.lowBatteryPercent)
        }
        checkHigh(Kind.PWM, sample.pwmPercent?.let(::abs), limits.pwmAlertPercent) {
            ThresholdAlert.PwmLoad(it, limits.pwmAlertPercent)
        }
    }

    private suspend fun checkHigh(
        kind: Kind,
        value: Float?,
        limit: Float,
        alert: (Float) -> ThresholdAlert,
    ) {
        val gate = gates.getValue(kind)
        if (value == null || !value.isFinite() || !limit.isFinite()) {
            gate.consecutive = 0
            return
        }
        if (!gate.armed && value < limit * HYSTERESIS_FACTOR) gate.armed = true
        updateGate(gate, value > limit, alert(value))
    }

    private suspend fun checkLow(
        kind: Kind,
        value: Float?,
        limit: Float,
        alert: (Float) -> ThresholdAlert,
    ) {
        val gate = gates.getValue(kind)
        if (value == null || !value.isFinite() || !limit.isFinite()) {
            gate.consecutive = 0
            return
        }
        if (!gate.armed && value > limit / HYSTERESIS_FACTOR) gate.armed = true
        updateGate(gate, value < limit, alert(value))
    }

    private suspend fun updateGate(gate: Gate, exceeded: Boolean, alert: ThresholdAlert) {
        if (!exceeded) {
            gate.consecutive = 0
            return
        }
        if (!gate.armed) return
        gate.consecutive += 1
        if (gate.consecutive < requiredSamples) return
        val now = clockMillis()
        if (gate.lastAt == Long.MIN_VALUE || now - gate.lastAt >= cooldownMillis) {
            _alerts.emit(alert)
            gate.lastAt = now
            gate.armed = false
        }
        gate.consecutive = 0
    }

    companion object {
        const val DEFAULT_REQUIRED_SAMPLES = 3
        const val DEFAULT_COOLDOWN_MILLIS = 10_000L
        const val HYSTERESIS_FACTOR = 0.95f
    }
}
