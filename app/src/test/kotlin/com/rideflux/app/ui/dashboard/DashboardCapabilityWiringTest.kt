/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.MiRegistrationState
import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.connection.ScooterHandshakeState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.device.PlevDevice
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.garage.InMemoryVehicleProfileStore
import com.rideflux.domain.garage.VehicleCalibration
import com.rideflux.domain.garage.VehicleProfileStore
import com.rideflux.domain.garage.vehicleProfile
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.PlevConnectionHandle
import com.rideflux.domain.repository.PlevRepository
import com.rideflux.protocol.repository.PlevRepositoryImpl
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import com.rideflux.domain.telemetry.ScooterTelemetry
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelBatteryPackStore
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelControlContract
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity
import com.rideflux.domain.wheel.WheelIdentityEvidence
import com.rideflux.domain.wheel.WheelSupport
import com.rideflux.domain.wheel.WheelTelemetryCoverage
import com.rideflux.protocol.familyi2.InmotionI2WheelCodec
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Production-wiring regressions for capability-driven controls and per-vehicle
 * profile scope.
 *
 * These drive the real [DashboardViewModel] against a scripted
 * [WheelConnection]. The fake connection still returns typed
 * [CommandOutcome]s, so the test can refuse a command exactly as VESC/Veteran
 * do without mocking the codec.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardCapabilityWiringTest {

    private val scheduler = TestCoroutineScheduler()
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val stores = mutableMapOf<DashboardViewModel, ViewModelStore>()
    private val settingsFlow = MutableStateFlow(AppSettings())
    private val settings = mockk<SettingsRepository>()
    private val noPacks = object : WheelBatteryPackStore {
        override val seriesCells = MutableStateFlow<Map<String, Int>>(emptyMap())
        override suspend fun setSeriesCells(address: String, cells: Int?) = Unit
    }
    private val app = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        every { settings.settings } returns settingsFlow
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        collectors.cancel()
        stores.values.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    @Test
    fun `read-only wheel refuses headlight without leaving a success state`() = runBlocking {
        val connection = FakeConnection(
            capabilities = InmotionI2WheelCodec.READ_ONLY_CAPABILITIES,
            support = WheelSupport.FAMILY_ONLY,
        )
        val viewModel = create(FakeWheelRepository(mapOf(ADDRESS to connection)), handle(ADDRESS))
        collect(viewModel.uiState)
        await("read-only capabilities") {
            viewModel.uiState.value.takeIf { it.capabilities != null }
        }

        viewModel.setHeadlight(true)
        val failure = await("unsupported failure") {
            viewModel.uiState.value.commandFailure?.takeIf {
                it.reason == CommandFailureReason.UNSUPPORTED
            }
        }
        assertEquals("set headlight", failure.operation)
        assertFalse(viewModel.uiState.value.headlightOn)
        assertTrue("a read-only session must not reach the encoder", connection.dispatched.isEmpty())
    }

    @Test
    fun `transport error rolls the headlight back and a later success clears the failure`() = runBlocking {
        val connection = FakeConnection(
            capabilities = InmotionI2WheelCodec.DEFAULT_CAPABILITIES,
            support = fullSupport(),
        )
        connection.dispatchOutcome = { CommandOutcome.TransportError(it, IOException("link lost")) }
        val viewModel = create(FakeWheelRepository(mapOf(ADDRESS to connection)), handle(ADDRESS))
        collect(viewModel.uiState)
        await("default capabilities") { viewModel.uiState.value.takeIf { it.capabilities != null } }

        viewModel.setHeadlight(true)
        await("transport failure") {
            viewModel.uiState.value.commandFailure?.takeIf {
                it.reason == CommandFailureReason.TRANSPORT_ERROR
            }
        }
        assertFalse("a failed write must not leave the switch on", viewModel.uiState.value.headlightOn)
        assertFalse(viewModel.uiState.value.headlightPending)

        connection.dispatchOutcome = { CommandOutcome.Success }
        viewModel.setHeadlight(true)
        await("successful headlight") { viewModel.uiState.value.takeIf { it.headlightOn } }
        assertNull(viewModel.uiState.value.commandFailure)
        assertEquals(listOf(WheelCommand.SetHeadlight(true), WheelCommand.SetHeadlight(true)), connection.dispatched.toList())
    }

    @Test
    fun `capabilities do not leak when switching A to B and back to A`() = runBlocking {
        val a = FakeConnection(
            capabilities = InmotionI2WheelCodec.DEFAULT_CAPABILITIES,
            support = fullSupport(),
        )
        val b = FakeConnection(
            capabilities = InmotionI2WheelCodec.READ_ONLY_CAPABILITIES,
            support = WheelSupport.FAMILY_ONLY,
        )
        val repository = FakeWheelRepository(mapOf(ADDRESS_A to a, ADDRESS_B to b))

        val viewA1 = create(repository, handle(ADDRESS_A))
        collect(viewA1.uiState)
        await("A capabilities") { viewA1.uiState.value.takeIf { it.capabilities?.headlight == true } }

        val viewB = create(repository, handle(ADDRESS_B))
        collect(viewB.uiState)
        await("B capabilities") { viewB.uiState.value.takeIf { it.capabilities != null } }
        assertFalse("B is read-only", viewB.uiState.value.capabilities!!.headlight)
        assertTrue(viewB.uiState.value.support!!.isLimited)

        val viewA2 = create(repository, handle(ADDRESS_A))
        collect(viewA2.uiState)
        await("A again") { viewA2.uiState.value.takeIf { it.capabilities?.headlight == true } }
        assertTrue("A's capability must not be inherited from B", viewA2.uiState.value.capabilities!!.headlight)
    }

    @Test
    fun `per-vehicle alert thresholds and calibration are scoped by address`() = runBlocking {
        val a = FakeConnection(
            capabilities = InmotionI2WheelCodec.READ_ONLY_CAPABILITIES,
            support = WheelSupport.FAMILY_ONLY,
        )
        val b = FakeConnection(
            capabilities = InmotionI2WheelCodec.READ_ONLY_CAPABILITIES,
            support = WheelSupport.FAMILY_ONLY,
        )
        val profiles = InMemoryVehicleProfileStore(
            listOf(
                vehicleProfile(
                    address = ADDRESS_A,
                    category = PlevCategory.WHEEL,
                    family = WheelFamily.G,
                    displayName = "A",
                    alertThresholds = AlertThresholds(speedLimitKmh = 20f),
                    calibration = VehicleCalibration(pitchOffsetDegrees = 2f),
                ),
                vehicleProfile(
                    address = ADDRESS_B,
                    category = PlevCategory.WHEEL,
                    family = WheelFamily.G,
                    displayName = "B",
                    alertThresholds = AlertThresholds(speedLimitKmh = 60f),
                    calibration = VehicleCalibration(pitchOffsetDegrees = -3f),
                ),
            ),
        )
        val repository = FakeWheelRepository(mapOf(ADDRESS_A to a, ADDRESS_B to b))
        val viewA = create(repository, handle(ADDRESS_A), profiles)
        val viewB = create(repository, handle(ADDRESS_B), profiles)
        collect(viewA.uiState)
        collect(viewB.uiState)
        await("A profile") { viewA.uiState.value.takeIf { it.vehicleProfile != null } }
        await("B profile") { viewB.uiState.value.takeIf { it.vehicleProfile != null } }

        repeat(3) { i ->
            a.telemetry.value = WheelTelemetry(timestampMillis = 1_000L + i, speedKmh = 50f, pitchAngleDegrees = 10f)
            b.telemetry.value = WheelTelemetry(timestampMillis = 1_000L + i, speedKmh = 50f, pitchAngleDegrees = 10f)
        }
        val alertA = await("A overspeed") { viewA.activeAlert.value as? DashboardAlert.Threshold }
        assertTrue(alertA.value is com.rideflux.domain.alert.ThresholdAlert.Overspeed)
        assertNull("B's 60 km/h limit must not inherit A's 20 km/h limit", viewB.activeAlert.value)
        assertEquals(12f, viewA.uiState.value.pitchAngleDegrees!!, 0.001f)
        assertEquals(7f, viewB.uiState.value.pitchAngleDegrees!!, 0.001f)
    }

    @Test
    fun `a fresh process restores scooter routing from the saved profile and never calls wheel connect`() = runBlocking {
        val scooter = FakeScooter()
        val plev = FakePlevRepository(PlevConnectionHandle.Scooter(scooter))
        val wheels = FakeWheelRepository(emptyMap())
        val profiles = InMemoryVehicleProfileStore(
            listOf(
                vehicleProfile(
                    address = ADDRESS_A,
                    category = PlevCategory.SCOOTER,
                    family = null,
                    displayName = "Ninebot",
                ),
            ),
        )
        val viewModel = create(
            wheels,
            handle(ADDRESS_A),
            profiles,
            plev,
        )
        collect(viewModel.uiState)
        await("scooter connect") { plev.requests.firstOrNull() }

        assertEquals(1, plev.requests.size)
        assertEquals(ADDRESS_A, plev.requests.single())
        assertTrue("wheel repository must not be used for a saved scooter", wheels.requests.isEmpty())
        assertEquals(PlevCategory.SCOOTER, viewModel.deviceCategory)
    }

    // ---- Helpers ------------------------------------------------------

    private fun fullSupport() = WheelSupport(
        identityEvidence = WheelIdentityEvidence.MODEL_AND_FIRMWARE,
        telemetryCoverage = WheelTelemetryCoverage.MODEL_LAYOUT,
        controlContract = WheelControlContract.FAMILY_CLOSED,
    )

    private fun handle(address: String, family: String? = null, category: String? = null) =
        SavedStateHandle(
            buildMap {
                put(DashboardViewModel.ARG_ADDRESS, address)
                if (family != null) put(DashboardViewModel.ARG_FAMILY, family)
                if (category != null) put(DashboardViewModel.ARG_CATEGORY, category)
            },
        )

    private fun create(
        repository: WheelRepository,
        handle: SavedStateHandle,
        profiles: VehicleProfileStore = InMemoryVehicleProfileStore(),
        plev: PlevRepository = PlevRepositoryImpl(repository),
    ): DashboardViewModel {
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DashboardViewModel(
                        repository,
                        settings,
                        noPacks,
                        app,
                        handle,
                        plev,
                        com.rideflux.domain.wheel.BegodeProfileStore.empty(),
                        profiles,
                    ) as T
            },
        )[DashboardViewModel::class.java]
        stores[viewModel] = store
        return viewModel
    }

    private fun collect(flow: StateFlow<*>) {
        collectors.launch { flow.collect { } }
    }

    private fun <T : Any> await(what: String, timeoutMillis: Long = 10_000L, probe: () -> T?): T {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            scheduler.runCurrent()
            probe()?.let { return it }
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private class FakeWheelRepository(
        private val connections: Map<String, WheelConnection>,
    ) : WheelRepository {
        val requests = CopyOnWriteArrayList<Pair<String, WheelFamily?>>()
        override fun scan(): Flow<List<DiscoveredWheel>> = flowOf(emptyList())
        override fun activeConnections(): Flow<Map<String, WheelConnection>> = flowOf(emptyMap())
        override suspend fun connect(address: String, expectedFamily: WheelFamily?): WheelConnection {
            requests += address to expectedFamily
            return connections[address] ?: throw IOException("no fake connection for $address")
        }
    }

    private class FakeConnection(
        capabilities: WheelCapabilities,
        support: WheelSupport,
    ) : WheelConnection {
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
        override val identity = MutableStateFlow<WheelIdentity?>(
            WheelIdentity(ADDRESS_A, WheelFamily.I2, "Test"),
        )
        override val capabilities = MutableStateFlow<WheelCapabilities?>(capabilities)
        override val support = MutableStateFlow<WheelSupport?>(support)
        override val telemetry = MutableStateFlow(WheelTelemetry.EMPTY)
        override val alerts = MutableSharedFlow<WheelAlert>(extraBufferCapacity = 32)
        override val speedKmh = MutableStateFlow<Float?>(null)
        override val voltageV = MutableStateFlow<Float?>(null)
        override val batteryPercent = MutableStateFlow<Float?>(null)
        override val currentA = MutableStateFlow<Float?>(null)
        override val mosTemperatureC = MutableStateFlow<Float?>(null)
        override val totalDistanceMetres = MutableStateFlow<Long?>(null)
        val dispatched = CopyOnWriteArrayList<WheelCommand>()
        @Volatile var dispatchOutcome: ((WheelCommand) -> CommandOutcome) = { CommandOutcome.Success }

        override suspend fun dispatch(command: WheelCommand): CommandOutcome {
            dispatched += command
            return dispatchOutcome(command)
        }

        override suspend fun close() = Unit
    }

    private class FakeScooter : ScooterConnection {
        override val device = ScooterDevice(ADDRESS_A, "Ninebot")
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
        override val telemetry = MutableStateFlow<ScooterTelemetry?>(null)
        override val handshakeState = MutableStateFlow(ScooterHandshakeState.READY_FOR_TELEMETRY)
        override val registrationState = MutableStateFlow(MiRegistrationState.NOT_REQUIRED)
        override val lockSupported = false
        override suspend fun start() = Unit
        override suspend fun close() = Unit
        override suspend fun lock(): CommandOutcome = CommandOutcome.Success
        override suspend fun unlock(): CommandOutcome = CommandOutcome.Success
        override suspend fun readRegister(register: Int, readLength: Int): ByteArray? = null
    }

    private class FakePlevRepository(
        private val handle: PlevConnectionHandle,
    ) : PlevRepository {
        val requests = CopyOnWriteArrayList<String>()
        override fun scan(): Flow<List<PlevDevice>> = flowOf(emptyList())
        override fun activeConnections(): Flow<Map<String, PlevConnectionHandle>> = flowOf(emptyMap())
        override suspend fun connect(address: String): PlevConnectionHandle {
            requests += address
            return handle
        }
    }



    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val ADDRESS_A = "AA:BB:CC:DD:EE:01"
        const val ADDRESS_B = "AA:BB:CC:DD:EE:02"
    }
}
