/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.preferences

import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DataStoreSettingsRepositoryTest {
    @Test
    fun settingsRoundTripNormalizesAddressesAndRemovesOptionalValues() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val context = RuntimeEnvironment.getApplication()
            val repository = DataStoreSettingsRepository(context, scope)
            assertEquals(AppSettings(), repository.current())

            val requested = AppSettings(
                alertThresholds = AlertThresholds(35f, 75f, 20f, 85f, false),
                useMetric = false,
                keepScreenOnDashboard = false,
                bridgeAutostart = true,
                bridgeStandbyAdvertiseLowLatency = true,
                hudPeerMac = " aa:bb:cc:dd:ee:ff ",
                ringKeyCode = 99,
                hudMirrorHorizontally = true,
                preferredGlassesMac = " 11:22:33:44:55:66 ",
            )
            repository.updateSettings(requested)
            val stored = repository.current()
            assertEquals("AA:BB:CC:DD:EE:FF", stored.hudPeerMac)
            assertEquals("11:22:33:44:55:66", stored.preferredGlassesMac)
            assertEquals(requested.copy(
                hudPeerMac = "AA:BB:CC:DD:EE:FF",
                preferredGlassesMac = "11:22:33:44:55:66",
            ), stored)
            assertEquals(stored, DataStoreSettingsRepository(context, scope).current())

            repository.setHudPeerMac("  ")
            repository.setPreferredGlassesMac(null)
            repository.setRingKeyCode(null)
            repository.setSpeedLimitKmh(50f)
            repository.setTemperatureLimitC(90f)
            repository.setLowBatteryPercent(30f)
            repository.setPwmAlertPercent(95f)
            repository.setAlertsEnabled(true)
            repository.setUseMetric(true)
            repository.setKeepScreenOnDashboard(true)
            repository.setBridgeAutostart(false)
            repository.setBridgeStandbyAdvertiseLowLatency(false)
            repository.setHudMirrorHorizontally(false)
            val changed = repository.current()
            assertNull(changed.hudPeerMac)
            assertNull(changed.preferredGlassesMac)
            assertNull(changed.ringKeyCode)
            assertEquals(AlertThresholds(50f, 90f, 30f, 95f, true), changed.alertThresholds)
            assertTrue(changed.useMetric)
            assertFalse(changed.bridgeAutostart)
            assertFalse(changed.hudMirrorHorizontally)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun rejectsInvalidThresholdsWithoutPersistingThem() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = DataStoreSettingsRepository(RuntimeEnvironment.getApplication(), scope)
            val before = repository.current()
            for (invalid in listOf(0f, 151f, Float.NaN, Float.POSITIVE_INFINITY)) {
                try {
                    repository.setSpeedLimitKmh(invalid)
                    fail("Accepted invalid speed limit $invalid")
                } catch (_: IllegalArgumentException) {
                    // Validation must reject the value before editing DataStore.
                }
            }
            assertEquals(before, repository.current())
        } finally {
            scope.cancel()
        }
    }
}
