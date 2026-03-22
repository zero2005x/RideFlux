package com.wheellog.next.di

import com.wheellog.next.domain.repository.EucRepository
import com.wheellog.next.domain.repository.TripRepository
import com.wheellog.next.domain.usecase.DeleteTripUseCase
import com.wheellog.next.domain.usecase.ExportTripsUseCase
import com.wheellog.next.domain.usecase.GetTripByIdUseCase
import com.wheellog.next.domain.usecase.ObserveTelemetryUseCase
import com.wheellog.next.domain.usecase.ObserveTripsUseCase
import com.wheellog.next.domain.usecase.ScanDevicesUseCase
import com.wheellog.next.domain.usecase.TripRecorder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {

    @Provides
    fun provideObserveTelemetryUseCase(
        repository: EucRepository,
    ): ObserveTelemetryUseCase = ObserveTelemetryUseCase(repository)

    @Provides
    fun provideScanDevicesUseCase(
        repository: EucRepository,
    ): ScanDevicesUseCase = ScanDevicesUseCase(repository)

    @Provides
    fun provideObserveTripsUseCase(
        repository: TripRepository,
    ): ObserveTripsUseCase = ObserveTripsUseCase(repository)

    @Provides
    fun provideDeleteTripUseCase(
        repository: TripRepository,
    ): DeleteTripUseCase = DeleteTripUseCase(repository)

    @Provides
    fun provideGetTripByIdUseCase(
        repository: TripRepository,
    ): GetTripByIdUseCase = GetTripByIdUseCase(repository)

    @Provides
    fun provideExportTripsUseCase(
        repository: TripRepository,
    ): ExportTripsUseCase = ExportTripsUseCase(repository)

    @Provides
    @Singleton
    fun provideTripRecorder(
        tripRepository: TripRepository,
    ): TripRecorder = TripRecorder(tripRepository)
}
