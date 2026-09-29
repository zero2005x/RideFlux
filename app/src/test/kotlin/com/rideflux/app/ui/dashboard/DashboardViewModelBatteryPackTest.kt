/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import com.rideflux.domain.wheel.WheelBatteryPackStore
import com.rideflux.domain.wheel.WheelFamily
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The battery-pack answer as the dashboard sees it: read for this wheel only, written under this
 * wheel's address. The wheel connection itself is irrelevant here, so connecting just fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelBatteryPackTest {

    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val packs = FakePacks()
    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        collectors.cancel()
        Dispatchers.resetMain()
    }

    private fun createViewModel() {
        val settings = mockk<SettingsRepository>()
        every { settings.settings } returns MutableStateFlow(AppSettings())
        val repository = object : WheelRepository {
            override fun scan() = flowOf(emptyList<DiscoveredWheel>())
            override fun activeConnections() = flowOf(emptyMap<String, WheelConnection>())
            override suspend fun connect(address: String, expectedFamily: WheelFamily?): WheelConnection =
                throw IOException("no wheel in this test")
        }
        viewModel = DashboardViewModel(
            repository,
            settings,
            packs,
            RuntimeEnvironment.getApplication(),
            SavedStateHandle(mapOf(DashboardViewModel.ARG_ADDRESS to ADDRESS)),
        )
    }

    @Test
    fun theAnswerIsReadForThisWheelOnlyAndFollowsChanges() {
        packs.cells.value = mapOf(
            WheelBatteryPackStore.key(ADDRESS) to 20,
            WheelBatteryPackStore.key("11:22:33:44:55:66") to 16,
        )
        createViewModel()
        // Already answered before the screen opened: no flicker through "unset".
        assertEquals(20, viewModel.batteryPackCells.value)

        collectors.launch { viewModel.batteryPackCells.collect { } }
        packs.cells.value = packs.cells.value - WheelBatteryPackStore.key(ADDRESS)
        assertNull(viewModel.batteryPackCells.value)
        packs.cells.value = packs.cells.value + (WheelBatteryPackStore.key(ADDRESS) to 24)
        assertEquals(24, viewModel.batteryPackCells.value)
    }

    @Test
    fun aWheelThatWasNeverAnsweredHasNoPackSize() {
        packs.cells.value = mapOf(WheelBatteryPackStore.key("11:22:33:44:55:66") to 16)
        createViewModel()

        assertNull(viewModel.batteryPackCells.value)
    }

    @Test
    fun choosingASizeStoresItForThisWheel() {
        createViewModel()

        viewModel.setBatteryPackCells(24)

        assertEquals(listOf(ADDRESS to 24), packs.writes.toList())
        assertEquals(24, packs.cells.value[WheelBatteryPackStore.key(ADDRESS)])
    }

    private class FakePacks : WheelBatteryPackStore {
        val cells = MutableStateFlow<Map<String, Int>>(emptyMap())
        val writes = CopyOnWriteArrayList<Pair<String, Int?>>()
        override val seriesCells get() = cells
        override suspend fun setSeriesCells(address: String, cells: Int?) {
            writes += address to cells
            val key = WheelBatteryPackStore.key(address)
            this.cells.value = if (cells == null) this.cells.value - key else this.cells.value + (key to cells)
        }
    }

    private companion object {
        const val ADDRESS = "aa:bb:cc:dd:ee:ff"
    }
}
