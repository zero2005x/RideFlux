/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripDaoTest {
    private lateinit var database: RideFluxDatabase
    private lateinit var dao: TripDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RideFluxDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.tripDao()
    }

    @After fun tearDown() = database.close()

    @Test
    fun createAppendFinishAndCascadeDelete() = runBlocking {
        val id = dao.insertTrip(
            TripEntity(0L, "AA:BB:CC:DD:EE:FF", "Test", 1L, null, 0.0, 0L, null, null, 80f, null, null, null)
        )
        dao.insertSample(
            TripSampleEntity(id, 2L, 10f, 84f, 2f, 79f, 20f, 40f, null, null, null)
        )
        val row = dao.observeTrip(id).first()
        assertNotNull(row)
        assertEquals(1, dao.observeSamples(id).first().size)
        dao.updateTrip(requireNotNull(row).copy(endedAtMillis = 2L, distanceMetres = 2.5))
        assertEquals(2L, dao.observeTrip(id).first()?.endedAtMillis)
        dao.deleteTrip(id)
        assertEquals(0, dao.observeSamples(id).first().size)
    }
}
