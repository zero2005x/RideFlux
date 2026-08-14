/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.di

import android.content.Context
import com.rideflux.core.location.FusedTripLocationSource
import com.rideflux.core.location.TripLocationSource
import com.rideflux.data.database.RideFluxDatabase
import com.rideflux.data.database.RoomTripRepository
import com.rideflux.data.preferences.DataStoreSettingsRepository
import com.rideflux.domain.ride.TripRepository
import com.rideflux.domain.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PersistenceModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RideFluxDatabase =
        RideFluxDatabase.create(context)

    @Provides
    @Singleton
    fun provideTripRepository(database: RideFluxDatabase): TripRepository =
        RoomTripRepository(database)

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): SettingsRepository = DataStoreSettingsRepository(context, scope)

    @Provides
    @Singleton
    fun provideLocationSource(@ApplicationContext context: Context): TripLocationSource =
        FusedTripLocationSource(context.applicationContext)
}
