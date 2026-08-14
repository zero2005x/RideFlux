/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.ride

import kotlinx.coroutines.flow.Flow

interface TripRepository {
    fun observeTrips(wheelAddress: String? = null): Flow<List<Trip>>
    fun observeTrip(tripId: Long): Flow<Trip?>
    fun observeSamples(tripId: Long): Flow<List<TripSample>>
    suspend fun createTrip(trip: Trip): Long
    suspend fun appendSample(sample: TripSample)
    suspend fun finishTrip(trip: Trip)
    suspend fun deleteTrip(tripId: Long)
    suspend fun clearAll()
    suspend fun recoverIncompleteTrips()
}
