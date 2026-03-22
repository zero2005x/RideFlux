package com.wheellog.next.data.database.di

import android.content.Context
import androidx.room.Room
import com.wheellog.next.data.database.RoomTripRepository
import com.wheellog.next.data.database.WheelLogDatabase
import com.wheellog.next.data.database.dao.TripDao
import com.wheellog.next.domain.repository.TripRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseProviderModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WheelLogDatabase =
        Room.databaseBuilder(
            context,
            WheelLogDatabase::class.java,
            "wheellog_next.db",
        ).build()

    @Provides
    fun provideTripDao(database: WheelLogDatabase): TripDao = database.tripDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseBindModule {

    @Binds
    @Singleton
    abstract fun bindTripRepository(impl: RoomTripRepository): TripRepository
}
