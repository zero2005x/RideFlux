package com.wheellog.next.data.ble.di

import com.wheellog.next.data.ble.KableEucRepository
import com.wheellog.next.domain.repository.EucRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BleModule {

    @Binds
    @Singleton
    abstract fun bindEucRepository(impl: KableEucRepository): EucRepository
}
