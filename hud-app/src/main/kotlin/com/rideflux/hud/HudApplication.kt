/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud

import android.app.Application
import com.rideflux.data.bridge.DiagnosticLog
import com.rideflux.data.bridge.DiagnosticLogs
import dagger.hilt.android.HiltAndroidApp
import java.io.File

/**
 * Hilt-enabled [Application] for the standalone HUD APK.
 *
 * The HUD APK ships its own DI graph (see [com.rideflux.hud.di.BleModule])
 * because Hilt aggregates @Modules per-APK — we cannot inherit the
 * :app module's bindings when :hud-app is installed on its own.
 */
@HiltAndroidApp
class HudApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The glasses clear logcat on every reboot, so keep the log in app-private storage;
        // HudActivity prints it for `adb shell dumpsys activity top --rideflux-diag`.
        DiagnosticLogs.install(DiagnosticLog(File(filesDir, "diagnostics")))
        DiagnosticLogs.record("hud", "process start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
}
