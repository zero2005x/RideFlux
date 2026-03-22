package com.wheellog.next.domain.repository

import com.wheellog.next.domain.model.Trip
import kotlinx.coroutines.flow.Flow

/**
 * Trip persistence repository — wraps Room DAO.
 * Implemented by :data:database.
 */
interface TripRepository {
    /** Observe all recorded trips, newest first. */
    fun observeTrips(): Flow<List<Trip>>

    /** Get a single trip by ID. */
    suspend fun getTripById(id: Long): Trip?

    /** Save a completed trip. */
    suspend fun saveTrip(trip: Trip): Long

    /** Delete a trip by ID. */
    suspend fun deleteTrip(id: Long)
}
