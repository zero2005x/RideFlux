/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.di

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
 * Qualifier for the single process-wide [CoroutineScope] that long-lived
 * repositories (e.g. [WheelRepository]) use to host their own jobs.
 *
 * Unlike `viewModelScope` or an activity-scoped [CoroutineScope], this
 * scope outlives configuration changes and navigation. It is cancelled
 * only when the application process is torn down.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Hilt DI module wiring the BLE data layer to the rest of the app.
 *
 * * [ApplicationScope] → a [SupervisorJob]-backed `CoroutineScope` on
 *   [Dispatchers.Default] so repository coroutines never crash the
 *   process when one of them throws.
 * * [WheelCodecFactory] → [WheelCodecFactoryImpl] from `:data:ble`.
 * * [WheelRepository] → [WheelRepositoryImpl] from `:data:ble`,
 *   constructed with the Android [Context] and the application scope.
 *
 * All bindings are `@Singleton` because a) BLE hardware allows only
 * one active GATT client per process, and b) we want every
 * `WheelConnection` consumer to share the same ref-counted repository
 * instance.
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
