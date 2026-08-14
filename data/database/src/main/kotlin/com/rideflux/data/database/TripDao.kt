/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startedAtMillis DESC")
    fun observeAllTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE wheelAddress = :wheelAddress ORDER BY startedAtMillis DESC")
    fun observeTripsForWheel(wheelAddress: String): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :tripId")
    fun observeTrip(tripId: Long): Flow<TripEntity?>

    @Query("SELECT * FROM trip_samples WHERE tripId = :tripId ORDER BY timestampMillis")
    fun observeSamples(tripId: Long): Flow<List<TripSampleEntity>>

    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: TripSampleEntity)

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTrip(tripId: Long)

    @Query("DELETE FROM trips")
    suspend fun clearAll()

    @Query(
        """
        UPDATE trips
        SET endedAtMillis = COALESCE(
            (SELECT MAX(timestampMillis) FROM trip_samples WHERE tripId = trips.id),
            startedAtMillis
        )
        WHERE endedAtMillis IS NULL
        """,
    )
    suspend fun recoverIncompleteTrips()
}
