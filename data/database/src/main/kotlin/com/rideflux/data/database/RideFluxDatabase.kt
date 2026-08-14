/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TripEntity::class, TripSampleEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RideFluxDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        const val NAME = "rideflux.db"

        fun create(context: Context): RideFluxDatabase =
            Room.databaseBuilder(context.applicationContext, RideFluxDatabase::class.java, NAME)
                .build()
    }
}
