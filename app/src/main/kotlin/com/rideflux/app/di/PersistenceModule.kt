/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.di

import android.content.Context
import com.rideflux.app.ui.bond.BondFileIo
import com.rideflux.app.ui.bond.ContentResolverBondFileIo
import com.rideflux.core.location.FusedTripLocationSource
import com.rideflux.data.preferences.AndroidKeystoreBondCipher
import com.rideflux.data.preferences.EncryptedFileBondStore
import com.rideflux.domain.bond.BondBackup
import com.rideflux.domain.bond.BondStore
import java.io.File
import com.rideflux.core.location.TripLocationSource
import com.rideflux.data.database.RideFluxDatabase
import com.rideflux.data.database.RoomTripRepository
import com.rideflux.data.preferences.DataStoreSettingsRepository
import com.rideflux.data.preferences.DataStoreWheelBatteryPackStore
import com.rideflux.domain.ride.TripRepository
import com.rideflux.domain.settings.SettingsRepository
import com.rideflux.domain.wheel.WheelBatteryPackStore
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
    fun provideWheelBatteryPackStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): WheelBatteryPackStore = DataStoreWheelBatteryPackStore(context, scope)

    @Provides
    @Singleton
    fun provideBondStore(@ApplicationContext context: Context): BondStore =
        EncryptedFileBondStore(File(context.filesDir, "bond_store.bin"), AndroidKeystoreBondCipher())

    @Provides
    @Singleton
    fun provideBondBackup(store: BondStore): BondBackup = BondBackup(store)

    @Provides
    @Singleton
    fun provideBondFileIo(@ApplicationContext context: Context): BondFileIo =
        ContentResolverBondFileIo(context.contentResolver)

    @Provides
    @Singleton
    fun provideLocationSource(@ApplicationContext context: Context): TripLocationSource =
        FusedTripLocationSource(context.applicationContext)
}
