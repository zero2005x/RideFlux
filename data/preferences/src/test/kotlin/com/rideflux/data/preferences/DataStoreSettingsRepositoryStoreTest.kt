/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.HudLayoutProfile
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Exercises [DataStoreSettingsRepository] against an in-memory [DataStore], so the read-failure
 * fallback and every optional-value branch can be reached without the process-wide file store.
 */
class DataStoreSettingsRepositoryStoreTest {
    @Test
    fun hudProfilesPersistPerGlassesAndRestoreWithSettings() = runBlocking {
        val repository = DataStoreSettingsRepository(InMemoryStore(), scope)
        val first = HudLayoutProfile(leftInset = 12, offsetX = -4, visibleItems = HudLayoutProfile.CLOCK)
        val second = HudLayoutProfile(fontPercent = 125, bottomInset = 9)

        repository.setHudProfile("AABBCCDDEEFF", first)
        repository.setHudProfile("112233445566", second)
        assertEquals(mapOf("AABBCCDDEEFF" to first, "112233445566" to second), repository.current().hudProfiles)

        repository.updateSettings(AppSettings(hudProfiles = mapOf("AABBCCDDEEFF" to second)))
        assertEquals(mapOf("AABBCCDDEEFF" to second), repository.current().hudProfiles)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setHudProfile("invalid:id", first) }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> })

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class InMemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private class FailingStore(private val error: Throwable) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw error
    }

    // ---- Read failures --------------------------------------------------

    @Test
    fun anUnreadableStoreYieldsTheDefaults() = runBlocking {
        for (failure in listOf(IOException("disk"), CorruptionException("bad bytes"))) {
            val repository = DataStoreSettingsRepository(FailingStore(failure), scope)

            assertEquals(AppSettings(), repository.current())
            assertEquals(AppSettings(), repository.settings.value)
        }
    }

    @Test
    fun aFailureThatIsNotAReadErrorIsNotSwallowed() {
        val repository = DataStoreSettingsRepository(FailingStore(IllegalStateException("bug")), scope)

        assertThrows(IllegalStateException::class.java) { runBlocking { repository.current() } }
    }

    // ---- Writes ----------------------------------------------------------

    @Test
    fun blankOptionalValuesAreStoredAsAbsent() = runBlocking {
        val repository = DataStoreSettingsRepository(InMemoryStore(), scope)

        repository.updateSettings(
            AppSettings(hudPeerMac = "  ", ringKeyCode = null, preferredGlassesMac = ""),
        )
        val stored = repository.current()

        assertNull(stored.hudPeerMac)
        assertNull(stored.ringKeyCode)
        assertNull(stored.preferredGlassesMac)
        assertEquals(AppSettings(), stored)
    }

    @Test
    fun optionalValuesCanBeSetAndThenClearedThroughTheFullUpdate() = runBlocking {
        val repository = DataStoreSettingsRepository(InMemoryStore(), scope)
        val filled = AppSettings(
            alertThresholds = AlertThresholds(30f, 70f, 15f, 80f, true),
            hudPeerMac = " aa:bb:cc:dd:ee:ff ",
            ringKeyCode = 87,
            preferredGlassesMac = " 11:22:33:44:55:66 ",
        )

        repository.updateSettings(filled)
        assertEquals("AA:BB:CC:DD:EE:FF", repository.current().hudPeerMac)
        assertEquals(87, repository.current().ringKeyCode)
        assertEquals("11:22:33:44:55:66", repository.current().preferredGlassesMac)

        repository.updateSettings(filled.copy(hudPeerMac = null, ringKeyCode = null, preferredGlassesMac = null))
        val cleared = repository.current()
        assertNull(cleared.hudPeerMac)
        assertNull(cleared.ringKeyCode)
        assertNull(cleared.preferredGlassesMac)
        assertEquals(filled.alertThresholds, cleared.alertThresholds)
    }

    @Test
    fun singleSettersNormaliseAndPersistEachValue() = runBlocking {
        val repository = DataStoreSettingsRepository(InMemoryStore(), scope)

        repository.setHudPeerMac(" aa:bb:cc:dd:ee:ff ")
        repository.setPreferredGlassesMac(" 11:22:33:44:55:66 ")
        repository.setRingKeyCode(42)
        repository.setHudMirrorHorizontally(true)
        repository.setBridgeAutostart(true)
        repository.setBridgeStandbyAdvertiseLowLatency(true)
        repository.setKeepScreenOnDashboard(false)
        repository.setUseMetric(false)
        repository.setAlertsEnabled(false)
        val stored = repository.current()

        assertEquals("AA:BB:CC:DD:EE:FF", stored.hudPeerMac)
        assertEquals("11:22:33:44:55:66", stored.preferredGlassesMac)
        assertEquals(42, stored.ringKeyCode)
        assertTrue(stored.hudMirrorHorizontally)
        assertTrue(stored.bridgeAutostart)
        assertTrue(stored.bridgeStandbyAdvertiseLowLatency)
        assertFalse(stored.keepScreenOnDashboard)
        assertFalse(stored.useMetric)
        assertFalse(stored.alertThresholds.enabled)
        assertEquals(stored, repository.settings.value)

        repository.setHudPeerMac(null)
        repository.setPreferredGlassesMac("   ")
        repository.setRingKeyCode(null)
        assertNull(repository.current().hudPeerMac)
        assertNull(repository.current().preferredGlassesMac)
        assertNull(repository.current().ringKeyCode)
    }

    @Test
    fun thresholdSettersAcceptTheirBoundsAndRejectEverythingOutside() = runBlocking {
        val repository = DataStoreSettingsRepository(InMemoryStore(), scope)

        repository.setSpeedLimitKmh(1f)
        repository.setTemperatureLimitC(150f)
        repository.setLowBatteryPercent(99f)
        repository.setPwmAlertPercent(100f)
        assertEquals(AlertThresholds(1f, 150f, 99f, 100f, true), repository.current().alertThresholds)

        val rejected: List<suspend () -> Unit> = listOf(
            { repository.setSpeedLimitKmh(0.5f) },
            { repository.setTemperatureLimitC(19f) },
            { repository.setTemperatureLimitC(151f) },
            { repository.setLowBatteryPercent(0f) },
            { repository.setLowBatteryPercent(100f) },
            { repository.setPwmAlertPercent(0.5f) },
            { repository.setPwmAlertPercent(Float.NaN) },
        )
        for (attempt in rejected) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { attempt() } }
        }
        assertEquals(AlertThresholds(1f, 150f, 99f, 100f, true), repository.current().alertThresholds)
    }
}
