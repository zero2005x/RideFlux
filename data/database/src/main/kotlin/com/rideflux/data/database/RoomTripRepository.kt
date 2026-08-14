/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.database

import androidx.room.withTransaction
import com.rideflux.domain.ride.Trip
import com.rideflux.domain.ride.TripRepository
import com.rideflux.domain.ride.TripSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomTripRepository(
    private val database: RideFluxDatabase,
) : TripRepository {
    private val dao = database.tripDao()

    override fun observeTrips(wheelAddress: String?): Flow<List<Trip>> =
        (wheelAddress?.let(dao::observeTripsForWheel) ?: dao.observeAllTrips())
            .map { rows -> rows.map(TripEntity::toDomain) }

    override fun observeTrip(tripId: Long): Flow<Trip?> =
        dao.observeTrip(tripId).map { it?.toDomain() }

    override fun observeSamples(tripId: Long): Flow<List<TripSample>> =
        dao.observeSamples(tripId).map { rows -> rows.map(TripSampleEntity::toDomain) }

    override suspend fun createTrip(trip: Trip): Long = dao.insertTrip(trip.toEntity())

    override suspend fun appendSample(sample: TripSample) = dao.insertSample(sample.toEntity())

    override suspend fun finishTrip(trip: Trip) {
        require(trip.id > 0L) { "A persisted trip id is required" }
        database.withTransaction { dao.updateTrip(trip.toEntity()) }
    }

    override suspend fun deleteTrip(tripId: Long) = dao.deleteTrip(tripId)
    override suspend fun clearAll() = dao.clearAll()
    override suspend fun recoverIncompleteTrips() = dao.recoverIncompleteTrips()
}

internal fun TripEntity.toDomain() = Trip(
    id, wheelAddress, wheelModel, startedAtMillis, endedAtMillis, distanceMetres,
    durationSeconds, maxSpeedKmh, avgSpeedKmh, startBatteryPercent,
    endBatteryPercent, maxPwmPercent, maxMosTemperatureC,
)

internal fun Trip.toEntity() = TripEntity(
    id, wheelAddress, wheelModel, startedAtMillis, endedAtMillis, distanceMetres,
    durationSeconds, maxSpeedKmh, avgSpeedKmh, startBatteryPercent,
    endBatteryPercent, maxPwmPercent, maxMosTemperatureC,
)

internal fun TripSampleEntity.toDomain() = TripSample(
    tripId, timestampMillis, speedKmh, voltageV, currentA, batteryPercent,
    pwmPercent, mosTemperatureC, latitudeDeg, longitudeDeg, altitudeM,
)

internal fun TripSample.toEntity() = TripSampleEntity(
    tripId, timestampMillis, speedKmh, voltageV, currentA, batteryPercent,
    pwmPercent, mosTemperatureC, latitudeDeg, longitudeDeg, altitudeM,
)
