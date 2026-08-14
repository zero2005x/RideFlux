/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "trips", indices = [Index("wheelAddress"), Index("startedAtMillis")])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val wheelAddress: String,
    val wheelModel: String?,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val distanceMetres: Double,
    val durationSeconds: Long,
    val maxSpeedKmh: Float?,
    val avgSpeedKmh: Float?,
    val startBatteryPercent: Float?,
    val endBatteryPercent: Float?,
    val maxPwmPercent: Float?,
    val maxMosTemperatureC: Float?,
)

@Entity(
    tableName = "trip_samples",
    primaryKeys = ["tripId", "timestampMillis"],
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tripId", "timestampMillis"])],
)
data class TripSampleEntity(
    val tripId: Long,
    val timestampMillis: Long,
    val speedKmh: Float?,
    val voltageV: Float?,
    val currentA: Float?,
    val batteryPercent: Float?,
    val pwmPercent: Float?,
    val mosTemperatureC: Float?,
    val latitudeDeg: Double?,
    val longitudeDeg: Double?,
    val altitudeM: Double?,
)
