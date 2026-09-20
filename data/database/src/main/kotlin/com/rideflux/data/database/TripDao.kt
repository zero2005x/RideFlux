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

    @Query("SELECT * FROM trips ORDER BY startedAtMillis ASC")
    suspend fun getAllTrips(): List<TripEntity>

    @Query("SELECT * FROM trip_samples ORDER BY tripId, timestampMillis ASC")
    suspend fun getAllSamples(): List<TripSampleEntity>

    @Query("SELECT * FROM trip_samples WHERE tripId = :tripId ORDER BY timestampMillis ASC")
    suspend fun getSamplesForTrip(tripId: Long): List<TripSampleEntity>

    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrips(trips: List<TripEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: TripSampleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<TripSampleEntity>)

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
