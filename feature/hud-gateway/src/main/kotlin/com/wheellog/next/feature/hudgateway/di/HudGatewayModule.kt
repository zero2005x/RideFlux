package com.wheellog.next.feature.hudgateway.di

import com.wheellog.next.domain.repository.HudGatewayRepository
import com.wheellog.next.feature.hudgateway.gatt.GattServerManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class HudGatewayModule {

    @Binds
    abstract fun bindHudGatewayRepository(
        impl: GattServerManager,
    ): HudGatewayRepository
}
