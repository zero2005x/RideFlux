/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.hud.source.BridgePeerCandidate
import com.rideflux.hud.storage.HudMacStore
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HudViewModelStateTest {
    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var store: HudMacStore
    private val models = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("hud_target", Context.MODE_PRIVATE).edit().clear().commit()
        store = HudMacStore(context)
        settings = mockk(relaxed = true)
        every { settings.settings } returns MutableStateFlow(AppSettings())
    }

    @After
    fun tearDown() {
        models.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun directTargetFromIntentWinsAndPersistsForNextLaunch() {
        store.write("11:22:33:44:55:66", WheelFamily.G)
        val model = createModel(
            HudViewModel.KEY_SOURCE to HudViewModel.SOURCE_DIRECT,
            HudViewModel.KEY_MAC to " aa:bb:cc:dd:ee:ff ",
            HudViewModel.KEY_FAMILY to WheelFamily.K.name,
        )
        assertEquals("AA:BB:CC:DD:EE:FF", store.readMac())
        assertEquals(WheelFamily.K, store.readFamily())
        assertFalse(model.uiState.value.awaitingTarget)

        val next = createModel(HudViewModel.KEY_SOURCE to HudViewModel.SOURCE_DIRECT)
        assertFalse(next.uiState.value.awaitingTarget)
    }

    @Test
    fun invalidDirectTargetLeavesHudAwaitingTarget() {
        val model = createModel(
            HudViewModel.KEY_SOURCE to HudViewModel.SOURCE_DIRECT,
            HudViewModel.KEY_MAC to "invalid",
        )
        assertNull(store.readMac())
        assertTrue(model.uiState.value.awaitingTarget)
    }

    @Test
    fun phonePairingPersistsIdentityAndControlsUpdateSettings() {
        val model = createModel(HudViewModel.KEY_SOURCE to HudViewModel.SOURCE_BRIDGE)
        assertTrue(model.hudVisible.value)
        model.toggleHudVisible()
        assertFalse(model.hudVisible.value)
        model.toggleHudVisible()
        assertTrue(model.hudVisible.value)

        model.startLearningRingKey()
        assertTrue(model.isLearningRingKey.value)
        model.recordRingKey(42)
        assertFalse(model.isLearningRingKey.value)
        coVerify(exactly = 1) { settings.setRingKeyCode(42) }

        model.pairPhone(BridgePeerCandidate("aa:bb:cc:dd:ee:ff", "phone", -50, "0102030405060708"))
        assertEquals("AA:BB:CC:DD:EE:FF", store.readPairedPhoneMac())
        assertEquals("0102030405060708", store.readPairedPhoneToken()?.joinToString("") {
            "%02x".format(it)
        })
        assertTrue(model.pairingCandidates.value.isEmpty())
        coVerify(exactly = 1) { settings.setHudPeerMac("aa:bb:cc:dd:ee:ff") }

        model.setSpeedLimit(40f)
        model.setTemperatureLimit(80f)
        model.setLowBatteryLimit(25f)
        model.setPwmLimit(90f)
        model.setHudMirrorHorizontally(true)
        model.resetRingKey()
        coVerify { settings.setSpeedLimitKmh(40f) }
        coVerify { settings.setTemperatureLimitC(80f) }
        coVerify { settings.setLowBatteryPercent(25f) }
        coVerify { settings.setPwmAlertPercent(90f) }
        coVerify { settings.setHudMirrorHorizontally(true) }
        coVerify { settings.setRingKeyCode(null) }
    }

    private fun createModel(vararg arguments: Pair<String, String>): HudViewModel {
        val model = HudViewModel(
            context,
            mockk<WheelRepository>(),
            store,
            settings,
            SavedStateHandle(arguments.toMap()),
        )
        models.put("model-${System.identityHashCode(model)}", model)
        return model
    }
}
