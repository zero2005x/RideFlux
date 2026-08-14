/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app

import android.app.Application
import com.rideflux.app.di.ApplicationScope
import com.rideflux.domain.ride.TripRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Process-wide [Application] subclass that bootstraps Hilt's
 * `SingletonComponent`.
 *
 * The `@HiltAndroidApp` annotation triggers Hilt's code generation
 * (`Hilt_RideFluxApplication`) and makes the
 * [dagger.hilt.components.SingletonComponent] available to every
 * `@AndroidEntryPoint` / `@HiltViewModel` further down the tree —
 * including [com.rideflux.app.ui.dashboard.DashboardViewModel] and
 * [com.rideflux.app.ui.scanner.ScannerViewModel].
 *
 * No custom initialization lives here yet; keep this class minimal
 * so it stays a reliable DI anchor point. Any cross-cutting init
 * (Timber, crash reporting, StrictMode, …) should go behind its own
 * `@EntryPoint` or `Initializer` rather than being added inline.
 */
@HiltAndroidApp
class RideFluxApplication : Application() {
    @Inject lateinit var tripRepository: TripRepository
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { tripRepository.recoverIncompleteTrips() }
    }
}
