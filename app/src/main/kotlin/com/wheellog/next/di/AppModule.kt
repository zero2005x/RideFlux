package com.wheellog.next.di

import com.wheellog.next.data.protocol.DefaultProtocolDecoder
import com.wheellog.next.domain.protocol.ProtocolDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Top-level Hilt module providing application-wide dependencies that cannot
 * use @Binds (e.g. pure Kotlin classes from non-Hilt modules, CoroutineScope).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideProtocolDecoder(): ProtocolDecoder = DefaultProtocolDecoder()
}
