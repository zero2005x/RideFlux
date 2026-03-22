package com.wheellog.next.data.preferences.di

import com.wheellog.next.data.preferences.DataStorePreferencesRepository
import com.wheellog.next.domain.repository.PreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PreferencesModule {

    @Binds
    @Singleton
    abstract fun bindPreferencesRepository(
        impl: DataStorePreferencesRepository,
    ): PreferencesRepository
}
