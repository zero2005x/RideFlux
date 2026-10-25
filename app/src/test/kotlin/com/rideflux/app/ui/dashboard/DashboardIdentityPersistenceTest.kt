/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.garage.InMemoryVehicleProfileStore
import com.rideflux.domain.garage.vehicleProfile
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelBatteryPackStore
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity
import com.rideflux.domain.wheel.WheelIdentityEvidence
import com.rideflux.domain.wheel.WheelControlContract
import com.rideflux.domain.wheel.WheelSupport
import com.rideflux.domain.wheel.WheelTelemetryCoverage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardIdentityPersistenceTest {
    private val address = "AA:BB:CC:DD:EE:21"
    private val other = "AA:BB:CC:DD:EE:22"

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `verified identity persists without replacing alias or another vehicle`() = kotlinx.coroutines.runBlocking {
        val store = InMemoryVehicleProfileStore(listOf(
            vehicleProfile(address, PlevCategory.WHEEL, null, "Rider alias"),
            vehicleProfile(other, PlevCategory.WHEEL, WheelFamily.G, "Other wheel"),
        ))
        val connection = FakeWheelConnection()
        val wheels = object : WheelRepository {
            override fun scan(): Flow<List<DiscoveredWheel>> = flowOf(emptyList())
            override fun activeConnections(): Flow<Map<String, WheelConnection>> = flowOf(emptyMap())
            override suspend fun connect(address: String, expectedFamily: WheelFamily?): WheelConnection = connection
        }
        val vmStore = ViewModelStore()
        val vm = DashboardViewModel(
            wheelRepository = wheels,
            settingsRepository = mockk<SettingsRepository>().also {
                every { it.settings } returns MutableStateFlow(AppSettings())
            },
            batteryPacks = NoPacks,
            appContext = RuntimeEnvironment.getApplication(),
            savedStateHandle = SavedStateHandle(
                mapOf(DashboardViewModel.ARG_ADDRESS to address, DashboardViewModel.ARG_CATEGORY to "WHEEL"),
            ),
            vehicleProfiles = store,
        )
        vmStore.put("dashboard", vm)
        val collector = launch { vm.uiState.collect {} }
        withTimeout(5_000) {
            while (vm.uiState.value.connectionState != ConnectionState.Ready) delay(10)
        }

        connection.support.value = WheelSupport(
            identityEvidence = WheelIdentityEvidence.MODEL_AND_FIRMWARE,
            telemetryCoverage = WheelTelemetryCoverage.MODEL_LAYOUT,
            controlContract = WheelControlContract.FAMILY_CLOSED,
        )
        connection.identity.value = WheelIdentity(
            address = address,
            family = WheelFamily.I2,
            modelName = "V11Y",
            firmwareVersion = "1.3.0",
        )
        withTimeout(5_000) {
            while (store.profile(address)?.modelName != "V11Y") delay(10)
        }
        val saved = store.profile(address)!!
        assertEquals("V11Y", saved.modelName)
        assertEquals("1.3.0", saved.firmwareVersion)
        assertEquals(WheelIdentityEvidence.MODEL_AND_FIRMWARE, saved.identityEvidence)
        assertEquals("Rider alias", saved.displayName)
        assertEquals(WheelFamily.I2, saved.family)
        assertNull("the other vehicle must not receive this identity", store.profile(other)!!.modelName)
        // Repository recreation from the persisted records keeps the identity
        // and the rider's alias.
        val restarted = InMemoryVehicleProfileStore(store.profiles.value)
        assertEquals("V11Y", restarted.profile(address)!!.modelName)
        assertEquals("1.3.0", restarted.profile(address)!!.firmwareVersion)
        assertEquals("Rider alias", restarted.profile(address)!!.displayName)

        // An identity without firmware is a hint, not evidence.
        connection.identity.value = WheelIdentity(
            address = address,
            family = WheelFamily.I2,
            modelName = "V12",
            firmwareVersion = null,
        )
        delay(100)
        assertEquals("V11Y", store.profile(address)!!.modelName)
        assertEquals("1.3.0", store.profile(address)!!.firmwareVersion)

        // Retiring this session must stop late identity updates.
        vmStore.clear()
        connection.identity.value = WheelIdentity(
            address = address,
            family = WheelFamily.I2,
            modelName = "V13",
            firmwareVersion = "2.0.0",
        )
        delay(100)
        assertEquals("V11Y", store.profile(address)!!.modelName)
        collector.cancel()
    }

    @Test
    fun `weak evidence followed by verified evidence never promotes or erases the pair`() = kotlinx.coroutines.runBlocking {
        val store = InMemoryVehicleProfileStore(listOf(
            vehicleProfile(address, PlevCategory.WHEEL, null, "Rider alias"),
        ))
        val connection = FakeWheelConnection()
        val wheels = object : WheelRepository {
            override fun scan(): Flow<List<DiscoveredWheel>> = flowOf(emptyList())
            override fun activeConnections(): Flow<Map<String, WheelConnection>> = flowOf(emptyMap())
            override suspend fun connect(address: String, expectedFamily: WheelFamily?): WheelConnection = connection
        }
        val vmStore = ViewModelStore()
        val vm = DashboardViewModel(
            wheelRepository = wheels,
            settingsRepository = mockk<SettingsRepository>().also {
                every { it.settings } returns MutableStateFlow(AppSettings())
            },
            batteryPacks = NoPacks,
            appContext = RuntimeEnvironment.getApplication(),
            savedStateHandle = SavedStateHandle(
                mapOf(DashboardViewModel.ARG_ADDRESS to address, DashboardViewModel.ARG_CATEGORY to "WHEEL"),
            ),
            vehicleProfiles = store,
        )
        vmStore.put("dashboard", vm)
        val collector = launch { vm.uiState.collect {} }
        try {
            withTimeout(5_000) {
                while (vm.uiState.value.connectionState != ConnectionState.Ready) delay(10)
            }
            connection.support.value = WheelSupport.FAMILY_ONLY
            connection.identity.value = WheelIdentity(
                address = address,
                family = WheelFamily.I2,
                modelName = "UNKNOWN-WEAK",
                firmwareVersion = "0.9.0",
            )
            delay(100)
            assertNull("weak evidence must not create a verified identity", store.profile(address)!!.modelName)

            connection.support.value = WheelSupport(
                identityEvidence = WheelIdentityEvidence.MODEL_AND_FIRMWARE,
                telemetryCoverage = WheelTelemetryCoverage.MODEL_LAYOUT,
                controlContract = WheelControlContract.FAMILY_CLOSED,
            )
            connection.identity.value = WheelIdentity(
                address = address,
                family = WheelFamily.I2,
                modelName = "V11Y",
                firmwareVersion = "1.3.0",
            )
            withTimeout(5_000) {
                while (store.profile(address)?.modelName != "V11Y") delay(10)
            }
            assertEquals(WheelIdentityEvidence.MODEL_AND_FIRMWARE, store.profile(address)!!.identityEvidence)

            connection.support.value = WheelSupport.FAMILY_ONLY
            connection.identity.value = WheelIdentity(
                address = address,
                family = WheelFamily.I2,
                modelName = "UNKNOWN-WEAK-2",
                firmwareVersion = "0.9.1",
            )
            delay(100)
            assertEquals("weak evidence must not erase the last verified pair", "V11Y", store.profile(address)!!.modelName)
            assertEquals("1.3.0", store.profile(address)!!.firmwareVersion)
            assertEquals("Rider alias", store.profile(address)!!.displayName)

            val restarted = InMemoryVehicleProfileStore(store.profiles.value)
            assertEquals("V11Y", restarted.profile(address)!!.modelName)
            assertEquals(WheelIdentityEvidence.MODEL_AND_FIRMWARE, restarted.profile(address)!!.identityEvidence)

            vmStore.clear()
            connection.identity.value = WheelIdentity(
                address = address,
                family = WheelFamily.I2,
                modelName = "LATE-V13",
                firmwareVersion = "2.0.0",
            )
            delay(100)
            assertEquals("V11Y", store.profile(address)!!.modelName)
        } finally {
            collector.cancel()
            vmStore.clear()
        }
    }

    private class FakeWheelConnection : WheelConnection {
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
        override val identity = MutableStateFlow<WheelIdentity?>(null)
        override val capabilities = MutableStateFlow<WheelCapabilities?>(null)
        override val support = MutableStateFlow<WheelSupport?>(null)
        override val telemetry = MutableStateFlow(WheelTelemetry.EMPTY)
        override val speedKmh = MutableStateFlow<Float?>(null)
        override val voltageV = MutableStateFlow<Float?>(null)
        override val batteryPercent = MutableStateFlow<Float?>(null)
        override val currentA = MutableStateFlow<Float?>(null)
        override val mosTemperatureC = MutableStateFlow<Float?>(null)
        override val totalDistanceMetres = MutableStateFlow<Long?>(null)
        override val alerts = MutableSharedFlow<WheelAlert>(extraBufferCapacity = 8)
        override suspend fun dispatch(command: WheelCommand): CommandOutcome =
            CommandOutcome.Unsupported(command)
        override suspend fun close() { state.value = ConnectionState.Disconnected }
    }

    private object NoPacks : WheelBatteryPackStore {
        override val seriesCells = MutableStateFlow<Map<String, Int>>(emptyMap())
        override suspend fun setSeriesCells(address: String, cells: Int?) = Unit
    }
}
