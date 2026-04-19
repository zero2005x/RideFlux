/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.di

import android.content.Context
import com.rideflux.data.ble.WheelCodecFactoryImpl
import com.rideflux.data.ble.WheelRepositoryImpl
import com.rideflux.domain.codec.WheelCodecFactory
import com.rideflux.domain.repository.WheelRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideWheelCodecFactoryImpl(): WheelCodecFactoryImpl = WheelCodecFactoryImpl()

    @Provides
    @Singleton
    fun bindWheelCodecFactory(impl: WheelCodecFactoryImpl): WheelCodecFactory = impl

    @Provides
    @Singleton
    fun provideWheelRepository(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        codecFactory: WheelCodecFactoryImpl,
    ): WheelRepository =
        WheelRepositoryImpl(
            context = context,
            rootScope = scope,
            codecFactory = codecFactory,
        )
}
