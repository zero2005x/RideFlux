/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.ride

import kotlinx.coroutines.flow.Flow

data class ImportResult(
    val tripsImported: Int,
    val tripsSkipped: Int,
    val samplesImported: Int,
)

interface TripRepository {
    fun observeTrips(wheelAddress: String? = null): Flow<List<Trip>>
    fun observeTrip(tripId: Long): Flow<Trip?>
    fun observeSamples(tripId: Long): Flow<List<TripSample>>
    suspend fun getAllTrips(): List<Trip> = emptyList()
    suspend fun getAllSamples(): List<TripSample> = emptyList()
    suspend fun getSamples(tripId: Long): List<TripSample> = emptyList()
    suspend fun createTrip(trip: Trip): Long
    suspend fun appendSample(sample: TripSample)
    suspend fun finishTrip(trip: Trip)
    suspend fun deleteTrip(tripId: Long)
    suspend fun clearAll()
    suspend fun recoverIncompleteTrips()
    suspend fun importTrips(
        tripsWithSamples: List<Pair<Trip, List<TripSample>>>,
        replaceAll: Boolean,
    ): ImportResult = ImportResult(0, 0, 0)
}
