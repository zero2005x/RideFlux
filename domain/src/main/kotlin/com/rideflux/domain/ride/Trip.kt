/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.ride

data class Trip(
    val id: Long = 0L,
    val wheelAddress: String,
    val wheelModel: String? = null,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val distanceMetres: Double = 0.0,
    val durationSeconds: Long = 0L,
    val maxSpeedKmh: Float? = null,
    val avgSpeedKmh: Float? = null,
    val startBatteryPercent: Float? = null,
    val endBatteryPercent: Float? = null,
    val maxPwmPercent: Float? = null,
    val maxMosTemperatureC: Float? = null,
)

data class TripSample(
    val tripId: Long,
    val timestampMillis: Long,
    val speedKmh: Float? = null,
    val voltageV: Float? = null,
    val currentA: Float? = null,
    val batteryPercent: Float? = null,
    val pwmPercent: Float? = null,
    val mosTemperatureC: Float? = null,
    val latitudeDeg: Double? = null,
    val longitudeDeg: Double? = null,
    val altitudeM: Double? = null,
)
