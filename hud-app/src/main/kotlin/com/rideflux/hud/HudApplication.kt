/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt-enabled [Application] for the standalone HUD APK.
 *
 * The HUD APK ships its own DI graph (see [com.rideflux.hud.di.BleModule])
 * because Hilt aggregates @Modules per-APK — we cannot inherit the
 * :app module's bindings when :hud-app is installed on its own.
 */
@HiltAndroidApp
class HudApplication : Application()
