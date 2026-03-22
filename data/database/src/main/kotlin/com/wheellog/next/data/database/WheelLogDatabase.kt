package com.wheellog.next.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.wheellog.next.data.database.dao.TripDao
import com.wheellog.next.data.database.entity.TripEntity

@Database(
    entities = [TripEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class WheelLogDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
}
