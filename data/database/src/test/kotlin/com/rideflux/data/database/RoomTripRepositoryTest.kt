/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.database

import androidx.room.Room
import com.rideflux.domain.ride.Trip
import com.rideflux.domain.ride.TripSample
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomTripRepositoryTest {
    private lateinit var database: RideFluxDatabase
    private lateinit var repository: RoomTripRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), RideFluxDatabase::class.java,
        ).build()
        repository = RoomTripRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun importSkipsDuplicateTripsAndRemapsSamplesToNewIds() = runBlocking {
        val trip = Trip(wheelAddress = "AA:BB:CC:DD:EE:FF", startedAtMillis = 100L)
        val sample = TripSample(tripId = 999L, timestampMillis = 101L, speedKmh = 25f)
        val result = repository.importTrips(
            listOf(trip to listOf(sample), trip to listOf(sample)),
            replaceAll = false,
        )
        assertEquals(1, result.tripsImported)
        assertEquals(1, result.tripsSkipped)
        assertEquals(1, result.samplesImported)
        val persisted = repository.getAllTrips().single()
        assertEquals("AA:BB:CC:DD:EE:FF", persisted.wheelAddress)
        assertEquals(persisted.id, repository.getAllSamples().single().tripId)
        assertEquals(listOf(persisted), repository.observeTrips(null).first())
        assertEquals(listOf(persisted), repository.observeTrips(persisted.wheelAddress).first())
        assertEquals(persisted, repository.observeTrip(persisted.id).first())
        assertEquals(1, repository.observeSamples(persisted.id).first().size)
    }

    @Test
    fun replaceAllDropsOldTripsAndFinishingRequiresPersistedId() = runBlocking {
        val old = Trip(wheelAddress = "AA:BB:CC:DD:EE:FF", startedAtMillis = 100L)
        val oldId = repository.createTrip(old)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.finishTrip(old) }
        }
        repository.finishTrip(old.copy(id = oldId, endedAtMillis = 200L, distanceMetres = 123.0))
        assertEquals(200L, repository.observeTrip(oldId).first()?.endedAtMillis)

        val replacement = Trip(wheelAddress = "11:22:33:44:55:66", startedAtMillis = 300L)
        val result = repository.importTrips(listOf(replacement to emptyList()), replaceAll = true)
        assertEquals(1, result.tripsImported)
        assertEquals(0, result.tripsSkipped)
        assertEquals(listOf(replacement.wheelAddress), repository.getAllTrips().map { it.wheelAddress })

        val id = repository.getAllTrips().single().id
        repository.appendSample(TripSample(tripId = id, timestampMillis = 301L))
        assertEquals(1, repository.getSamples(id).size)
        repository.deleteTrip(id)
        assertEquals(emptyList<Trip>(), repository.getAllTrips())
    }
}
