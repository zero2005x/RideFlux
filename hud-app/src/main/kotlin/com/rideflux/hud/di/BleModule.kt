/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.di

import android.content.Context
import android.util.Log
import com.rideflux.data.ble.BleWheelCodecFactory
import com.rideflux.data.ble.WheelCodecFactoryImpl
import com.rideflux.data.ble.WheelRepositoryImpl
import com.rideflux.domain.codec.WheelCodecFactory
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.data.preferences.DataStoreSettingsRepository
import com.rideflux.domain.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Process-wide CoroutineScope qualifier. Mirrors the same binding in
 * :app so that both APKs use a consistent lifetime model for
 * repository-owned jobs.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Hilt wiring for the standalone HUD APK. Structurally identical to
 * the :app module's BleModule — only the package differs so that
 * Hilt's per-APK aggregation does not clash when both APKs are
 * installed side-by-side.
 */
@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    private const val TAG = "HudBleModule"


    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, t ->
                    Log.e(TAG, "Unhandled exception in ApplicationScope", t)
                },
        )

    @Provides
    @Singleton
    fun provideWheelCodecFactoryImpl(): WheelCodecFactoryImpl = WheelCodecFactoryImpl()

    @Provides
    @Singleton
    fun bindWheelCodecFactory(impl: WheelCodecFactoryImpl): WheelCodecFactory = impl

    @Provides
    @Singleton
    fun bindBleWheelCodecFactory(impl: WheelCodecFactoryImpl): BleWheelCodecFactory = impl

    @Provides
    @Singleton
    fun provideWheelRepository(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        codecFactory: BleWheelCodecFactory,
    ): WheelRepository =
        WheelRepositoryImpl(
            context = context,
            rootScope = scope,
            codecFactory = codecFactory,
        )

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): SettingsRepository = DataStoreSettingsRepository(context, scope)
}
