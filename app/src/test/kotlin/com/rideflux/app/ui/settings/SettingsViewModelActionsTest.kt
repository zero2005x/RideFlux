/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.settings

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModelStore
import com.rideflux.app.backup.TripBackupManager
import com.rideflux.app.bridge.ApprovedGlasses
import com.rideflux.app.bridge.ApprovedGlassesStore
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The settings screen's view model: ring-key learning, bonded devices and approved glasses. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelActionsTest {
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val models = ViewModelStore()
    private lateinit var model: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.settings } returns MutableStateFlow(AppSettings())
        RingKeyLearner.stopListening()
        model = SettingsViewModel(RuntimeEnvironment.getApplication(), settings, mockk<TripBackupManager>())
        models.put("settings", model)
    }

    @After
    fun tearDown() {
        RingKeyLearner.stopListening()
        models.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun mirroringChoiceReachesTheRepository() {
        model.setHudMirrorHorizontally(true)
        coVerify { settings.setHudMirrorHorizontally(true) }
    }

    @Test
    fun learningARingKeyStoresItAndStopsListening() {
        assertFalse(model.isLearningRingKey.value)
        model.startLearningRingKey()
        assertTrue(model.isLearningRingKey.value)

        assertTrue(RingKeyLearner.onKeyEvent(24))

        assertFalse(model.isLearningRingKey.value)
        coVerify { settings.setRingKeyCode(24) }
    }

    @Test
    fun stoppingOrResettingClearsTheListeningState() {
        model.startLearningRingKey()
        model.stopLearningRingKey()
        assertFalse(model.isLearningRingKey.value)

        model.startLearningRingKey()
        model.resetRingKey()
        assertFalse(model.isLearningRingKey.value)
        coVerify { settings.setRingKeyCode(null) }
    }

    @Test
    fun bondedDevicesAreListedWithAFallbackNameForUnnamedOnes() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val named = adapter.getRemoteDevice("AA:BB:CC:DD:EE:01")
        val unnamed = adapter.getRemoteDevice("AA:BB:CC:DD:EE:02")
        shadowOf(named).setName("Rokid Glasses")
        shadowOf(adapter).setBondedDevices(setOf(named, unnamed))

        val devices = model.getBondedBluetoothDevices().associate { it.address to it.name }

        assertEquals("Rokid Glasses", devices["AA:BB:CC:DD:EE:01"])
        assertEquals("Bluetooth Device", devices["AA:BB:CC:DD:EE:02"])
    }

    @Test
    fun removingApprovedGlassesForgetsThem() {
        val app = RuntimeEnvironment.getApplication()
        val glasses = ApprovedGlasses(tokenHex = "0102030405060708", mac = "AA:BB:CC:DD:EE:FF", shortCode = "A1B2")
        ApprovedGlassesStore.add(app, glasses)
        assertTrue(model.approvedGlasses.value.contains(glasses))

        model.removeApprovedGlasses(glasses)

        assertFalse(model.approvedGlasses.value.contains(glasses))
    }
}
