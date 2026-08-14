/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.recording

import com.rideflux.domain.telemetry.WheelTelemetry
import kotlin.math.abs
import kotlin.math.max

data class TripStatistics(
    val distanceMetres: Double = 0.0,
    val durationSeconds: Long = 0L,
    val maxSpeedKmh: Float? = null,
    val avgSpeedKmh: Float? = null,
    val startBatteryPercent: Float? = null,
    val endBatteryPercent: Float? = null,
    val maxPwmPercent: Float? = null,
    val maxMosTemperatureC: Float? = null,
)

/** Incremental, unit-testable ride statistics kept in canonical SI units. */
class TripStatisticsAccumulator {
    private var lastTimestampMillis: Long? = null
    private var rideStarted = false
    private var distanceMetres = 0.0
    private var ridingMillis = 0L
    private var averageWeightedKmhMillis = 0.0
    private var averageMillis = 0L
    private var maxSpeed: Float? = null
    private var startBattery: Float? = null
    private var endBattery: Float? = null
    private var maxPwm: Float? = null
    private var maxTemperature: Float? = null

    fun add(telemetry: WheelTelemetry, timestampMillis: Long): TripStatistics {
        val speed = telemetry.speedKmh?.takeIf(Float::isFinite)?.let(::abs) ?: 0f
        if (!rideStarted && speed > RIDE_THRESHOLD_KMH) rideStarted = true
        if (startBattery == null) startBattery = telemetry.batteryPercent
        telemetry.batteryPercent?.let { endBattery = it }
        telemetry.pwmPercent?.takeIf(Float::isFinite)?.let(::abs)?.let { value ->
            maxPwm = max(maxPwm ?: value, value)
        }
        telemetry.mosTemperatureC?.takeIf(Float::isFinite)?.let { value ->
            maxTemperature = max(maxTemperature ?: value, value)
        }
        maxSpeed = max(maxSpeed ?: speed, speed)

        lastTimestampMillis?.let { previous ->
            val dt = (timestampMillis - previous).coerceIn(0L, MAX_SAMPLE_GAP_MILLIS)
            if (rideStarted && dt > 0L) {
                distanceMetres += speed.toDouble() / 3.6 * dt / 1_000.0
                averageWeightedKmhMillis += speed.toDouble() * dt
                averageMillis += dt
                if (speed > RIDE_THRESHOLD_KMH) ridingMillis += dt
            }
        }
        lastTimestampMillis = timestampMillis
        return snapshot()
    }

    fun snapshot() = TripStatistics(
        distanceMetres = distanceMetres,
        durationSeconds = ridingMillis / 1_000L,
        maxSpeedKmh = maxSpeed,
        avgSpeedKmh = if (averageMillis > 0L) (averageWeightedKmhMillis / averageMillis).toFloat() else null,
        startBatteryPercent = startBattery,
        endBatteryPercent = endBattery,
        maxPwmPercent = maxPwm,
        maxMosTemperatureC = maxTemperature,
    )

    companion object {
        const val RIDE_THRESHOLD_KMH = 1f
        const val MAX_SAMPLE_GAP_MILLIS = 5_000L
    }
}
