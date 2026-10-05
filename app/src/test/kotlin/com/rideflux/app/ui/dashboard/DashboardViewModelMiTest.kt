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
import com.rideflux.domain.connection.MiRegistrationState
import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.connection.ScooterHandshakeState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.PlevConnectionHandle
import com.rideflux.domain.repository.PlevRepository
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import com.rideflux.domain.telemetry.ScooterTelemetry
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
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelMiTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val collectors = CoroutineScope(SupervisorJob() + dispatcher)
    private val stores = mutableMapOf<DashboardViewModel, ViewModelStore>()
    private val settingsFlow = MutableStateFlow(AppSettings())
    private val settings = mockk<SettingsRepository>()

    private val noPacks = object : WheelBatteryPackStore {
        override val seriesCells = MutableStateFlow<Map<String, Int>>(emptyMap())
        override suspend fun setSeriesCells(address: String, cells: Int?) = Unit
    }

    private val unusedWheelRepo = object : WheelRepository {
        override fun scan() = flowOf(emptyList<DiscoveredWheel>())
        override fun activeConnections() = flowOf(emptyMap<String, WheelConnection>())
        override suspend fun connect(address: String, expectedFamily: WheelFamily?): WheelConnection =
            throw UnsupportedOperationException("Scooter tests should not call wheelRepository.connect")
    }

    @Before
    fun setUp() {
        every { settings.settings } returns settingsFlow
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        collectors.cancel()
        stores.values.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private class FakeMiScooterConnection(
        override val device: ScooterDevice = ScooterDevice(ADDRESS, "Mi Scooter Pro 2"),
        override val state: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.ScooterHandshaking),
        override val telemetry: MutableStateFlow<ScooterTelemetry?> = MutableStateFlow(null),
        override val handshakeState: MutableStateFlow<ScooterHandshakeState> = MutableStateFlow(ScooterHandshakeState.UNBONDED),
        override val registrationState: MutableStateFlow<MiRegistrationState> = MutableStateFlow(MiRegistrationState.CONSENT_REQUIRED),
    ) : ScooterConnection {
        var closeCalled = false
        var registeredConfirmed: Boolean? = null
        override suspend fun start() = Unit
        override suspend fun close() { closeCalled = true }
        override suspend fun lock(): CommandOutcome = CommandOutcome.Unsupported(WheelCommand.Raw(byteArrayOf(0x70)))
        override suspend fun unlock(): CommandOutcome = CommandOutcome.Unsupported(WheelCommand.Raw(byteArrayOf(0x71)))
        override suspend fun readRegister(register: Int, readLength: Int): ByteArray? = null
        override suspend fun registerAfterUserConfirmation(confirmed: Boolean): Boolean {
            registeredConfirmed = confirmed
            if (confirmed) {
                registrationState.value = MiRegistrationState.WAITING_FOR_POWER_BUTTON
                return true
            }
            return false
        }
    }

    private class FakePlevRepository(private val connection: ScooterConnection) : PlevRepository {
        override fun scan(): kotlinx.coroutines.flow.Flow<List<com.rideflux.domain.device.PlevDevice>> =
            flowOf(emptyList())
        override suspend fun connect(address: String): PlevConnectionHandle =
            PlevConnectionHandle.Scooter(connection)
        override fun activeConnections(): kotlinx.coroutines.flow.Flow<Map<String, PlevConnectionHandle>> =
            flowOf(emptyMap())
    }

    private fun createViewModel(connection: ScooterConnection): DashboardViewModel {
        val handle = SavedStateHandle(mapOf(
            DashboardViewModel.ARG_ADDRESS to ADDRESS,
            DashboardViewModel.ARG_CATEGORY to PlevCategory.SCOOTER.name,
        ))
        val vm = DashboardViewModel(
            wheelRepository = unusedWheelRepo,
            settingsRepository = settings,
            batteryPacks = noPacks,
            appContext = RuntimeEnvironment.getApplication(),
            savedStateHandle = handle,
            plevRepository = FakePlevRepository(connection),
        )
        val store = ViewModelStore()
        stores[vm] = store
        collectors.launch { vm.uiState.collect {} }
        return vm
    }

    private fun until(what: String, timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            scheduler.runCurrent()
            if (condition()) return
            if (System.nanoTime() > deadline) org.junit.Assert.fail("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    @Test
    fun `when registration is required uiState reflects CONSENT_REQUIRED`() {
        val connection = FakeMiScooterConnection()
        val viewModel = createViewModel(connection)

        until("uiState reflects CONSENT_REQUIRED") {
            viewModel.uiState.value.miRegistrationState == MiRegistrationState.CONSENT_REQUIRED
        }
        assertNull(viewModel.uiState.value.handshakeRemainingSeconds)
    }

    @Test
    fun `user confirms registration and state progresses to WAITING_FOR_POWER_BUTTON with countdown`() {
        val connection = FakeMiScooterConnection()
        val viewModel = createViewModel(connection)

        until("uiState reflects CONSENT_REQUIRED") {
            viewModel.uiState.value.miRegistrationState == MiRegistrationState.CONSENT_REQUIRED
        }

        viewModel.onMiRegistrationConsent(true)
        until("registeredConfirmed set to true") { connection.registeredConfirmed == true }
        until("uiState reflects WAITING_FOR_POWER_BUTTON") {
            viewModel.uiState.value.miRegistrationState == MiRegistrationState.WAITING_FOR_POWER_BUTTON
        }
        assertEquals(30, viewModel.uiState.value.handshakeRemainingSeconds)

        scheduler.advanceTimeBy(1000)
        scheduler.runCurrent()
        assertEquals(29, viewModel.uiState.value.handshakeRemainingSeconds)

        scheduler.advanceTimeBy(2000)
        scheduler.runCurrent()
        assertEquals(27, viewModel.uiState.value.handshakeRemainingSeconds)

        connection.registrationState.value = MiRegistrationState.AUTHENTICATING
        until("uiState reflects AUTHENTICATING") {
            viewModel.uiState.value.miRegistrationState == MiRegistrationState.AUTHENTICATING
        }
        assertNull(viewModel.uiState.value.handshakeRemainingSeconds)

        connection.registrationState.value = MiRegistrationState.NOT_REQUIRED
        connection.state.value = ConnectionState.Ready
        until("uiState reflects Ready") {
            viewModel.uiState.value.connectionState == ConnectionState.Ready &&
                viewModel.uiState.value.miRegistrationState == MiRegistrationState.NOT_REQUIRED
        }
    }

    @Test
    fun `user rejects registration and connection is closed`() {
        val connection = FakeMiScooterConnection()
        val viewModel = createViewModel(connection)

        until("uiState reflects CONSENT_REQUIRED") {
            viewModel.uiState.value.miRegistrationState == MiRegistrationState.CONSENT_REQUIRED
        }

        viewModel.onMiRegistrationConsent(false)
        until("registeredConfirmed set to false") { connection.registeredConfirmed == false }
        assertTrue(connection.closeCalled)
    }

    @Test
    fun `ninebot pairing countdown progression and cancellation`() {
        val connection = FakeMiScooterConnection()
        connection.registrationState.value = MiRegistrationState.NOT_REQUIRED
        val viewModel = createViewModel(connection)

        until("uiState is connected") { viewModel.uiState.value.deviceModel == "Mi Scooter Pro 2" }
        connection.handshakeState.value = ScooterHandshakeState.WAITING_FOR_USER_CONFIRMATION
        until("countdown started at 20") { viewModel.uiState.value.handshakeRemainingSeconds == 20 }

        scheduler.advanceTimeBy(1000)
        scheduler.runCurrent()
        assertEquals(19, viewModel.uiState.value.handshakeRemainingSeconds)

        connection.handshakeState.value = ScooterHandshakeState.PAIRING_COMPLETE
        until("countdown cleared") { viewModel.uiState.value.handshakeRemainingSeconds == null }
    }

    @Test
    fun `onMiRegistrationConsent does nothing when connection is not a scooter`() {
        val handle = SavedStateHandle(mapOf(
            DashboardViewModel.ARG_ADDRESS to ADDRESS,
            DashboardViewModel.ARG_CATEGORY to PlevCategory.WHEEL.name,
        ))
        val fakeWheelConnection = mockk<WheelConnection>(relaxed = true)
        val wheelRepo = object : WheelRepository {
            override fun scan() = flowOf(emptyList<DiscoveredWheel>())
            override fun activeConnections() = flowOf(emptyMap<String, WheelConnection>())
            override suspend fun connect(address: String, expectedFamily: WheelFamily?): WheelConnection =
                fakeWheelConnection
        }
        val vm = DashboardViewModel(
            wheelRepository = wheelRepo,
            settingsRepository = settings,
            batteryPacks = noPacks,
            appContext = RuntimeEnvironment.getApplication(),
            savedStateHandle = handle,
        )
        stores[vm] = ViewModelStore()
        collectors.launch { vm.uiState.collect {} }
        until("wheel connected") { vm.uiState.value.deviceCategory == PlevCategory.WHEEL }

        vm.onMiRegistrationConsent(true)
        scheduler.runCurrent()
        // No crash, gracefully returns
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
