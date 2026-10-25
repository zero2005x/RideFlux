/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.scanner

import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.PlevConnectionHandle
import com.rideflux.domain.device.PlevDevice
import com.rideflux.domain.repository.PlevRepository
import com.rideflux.domain.repository.ScooterRepository
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.wheel.WheelFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
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
import org.robolectric.annotation.Config

/**
 * Scan lifecycle: one collector at a time, both categories on one stream,
 * failures surfaced to the rider, and stop really stopping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerViewModelScanTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Wheels : WheelRepository {
        override fun scan(): Flow<List<DiscoveredWheel>> = flowOf(emptyList())
        override fun activeConnections(): Flow<Map<String, com.rideflux.domain.connection.WheelConnection>> =
            flowOf(emptyMap())
        override suspend fun connect(address: String, expectedFamily: WheelFamily?):
            com.rideflux.domain.connection.WheelConnection = error("not used")
    }

    private class Scooters : ScooterRepository {
        override fun scan(): Flow<List<ScooterDevice>> = flowOf(emptyList())
        override fun activeConnections(): Flow<Map<String, com.rideflux.domain.connection.ScooterConnection>> =
            flowOf(emptyMap())
        override suspend fun connect(address: String):
            com.rideflux.domain.connection.ScooterConnection = error("not used")
    }

    private class StubPlev(
        private val wheels: () -> Flow<List<DiscoveredWheel>>,
        private val scooters: Flow<List<ScooterDevice>> = flowOf(emptyList()),
    ) : PlevRepository {
        var activeCollectors = 0
        var starts = 0

        override fun scan(): Flow<List<PlevDevice>> = flowOf(emptyList())

        override fun scanWheels(): Flow<List<DiscoveredWheel>> {
            val stream = wheels()
            return stream
                .onStart {
                    starts += 1
                    activeCollectors += 1
                }
                .onCompletion { activeCollectors -= 1 }
        }

        override fun scanScooters(): Flow<List<ScooterDevice>> = scooters

        override suspend fun connect(address: String): PlevConnectionHandle = error("not used")

        override fun activeConnections(): Flow<Map<String, PlevConnectionHandle>> = flowOf(emptyMap())
    }

    @Test
    fun `a scan publishes both categories and clears when stopped`() {
        val wheels = MutableSharedFlow<List<DiscoveredWheel>>(replay = 1)
        val scooters = MutableSharedFlow<List<ScooterDevice>>(replay = 1)
        val repo = StubPlev({ wheels }, scooters)
        val viewModel = ScannerViewModel(Wheels(), Scooters(), plevRepository = repo)

        viewModel.startScan()
        assertTrue(viewModel.uiState.value.isScanning)

        wheels.tryEmit(listOf(DiscoveredWheel("AA:BB:CC:DD:EE:01", "Tourer", -50, WheelFamily.I2)))
        scooters.tryEmit(listOf(ScooterDevice("02:00:00:00:00:03", "ES2")))

        assertEquals(1, viewModel.uiState.value.devices.size)
        assertEquals(1, viewModel.uiState.value.scooters.size)

        viewModel.stopScan()

        assertFalse(viewModel.uiState.value.isScanning)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `starting twice keeps a single collector`() {
        val wheels = MutableSharedFlow<List<DiscoveredWheel>>(replay = 1)
        val repo = StubPlev({ wheels })
        val viewModel = ScannerViewModel(Wheels(), Scooters(), plevRepository = repo)

        viewModel.startScan()
        viewModel.startScan()

        assertEquals(1, repo.starts)
        assertEquals(1, repo.activeCollectors)
    }

    @Test
    fun `a failing scan stream stops scanning and explains why`() {
        val failing = flow<List<DiscoveredWheel>> { throw IllegalStateException("adapter off") }
        val viewModel = ScannerViewModel(Wheels(), Scooters(), plevRepository = StubPlev({ failing }))

        viewModel.startScan()

        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals("adapter off", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `a failure before collection is reported too`() {
        val repo = StubPlev({ throw IllegalStateException("scanner unavailable") })
        val viewModel = ScannerViewModel(Wheels(), Scooters(), plevRepository = repo)

        viewModel.startScan()

        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals("scanner unavailable", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `a finished stream clears the scanning flag`() {
        val viewModel = ScannerViewModel(
            Wheels(),
            Scooters(),
            plevRepository = StubPlev({ flowOf(listOf(DiscoveredWheel("AA:BB:CC:DD:EE:02", "One", -60, null))) }),
        )

        viewModel.startScan()

        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals(1, viewModel.uiState.value.devices.size)
    }
}
