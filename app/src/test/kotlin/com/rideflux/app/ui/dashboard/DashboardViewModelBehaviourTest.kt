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
import com.rideflux.domain.alert.ThresholdAlert
import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives [DashboardViewModel] against a scripted wheel connection.
 *
 * Connecting runs on [Dispatchers.IO], so the tests poll for the effect
 * instead of assuming an order; virtual time (alert expiry) is advanced on
 * the scheduler behind the replaced main dispatcher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelBehaviourTest {

    private val scheduler = TestCoroutineScheduler()
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val stores = mutableMapOf<DashboardViewModel, ViewModelStore>()
    private val settingsFlow = MutableStateFlow(AppSettings())
    private val settings = mockk<SettingsRepository>()

    /** The battery-pack answer is irrelevant here (see DashboardViewModelBatteryPackTest). */
    private val noPacks = object : WheelBatteryPackStore {
        override val seriesCells = MutableStateFlow<Map<String, Int>>(emptyMap())
        override suspend fun setSeriesCells(address: String, cells: Int?) = Unit
    }

    @Before
    fun setUp() {
        every { settings.settings } returns settingsFlow
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        shadowOf(RuntimeEnvironment.getApplication()).clearStartedServices()
    }

    @After
    fun tearDown() {
        collectors.cancel()
        stores.values.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    // ---- Construction -------------------------------------------------

    @Test
    fun connectsToTheRequestedWheelAndProjectsItsStateIntoTheUiState() {
        val connection = FakeConnection()
        val repository = FakeRepository { connection }
        val viewModel = create(repository, handle(family = "G"))
        collect(viewModel.uiState)

        connection.identity.value = WheelIdentity(ADDRESS, WheelFamily.G, "Test wheel")
        connection.telemetry.value = WheelTelemetry(
            timestampMillis = 1_000L,
            speedKmh = -12f,
            voltageV = 80f,
            currentA = 5f,
            phaseCurrentA = 7f,
            pwmPercent = 40f,
            batteryPercent = 50f,
            batteryVoltageV = 79f,
            mosTemperatureC = 35f,
            motorTemperatureC = 36f,
            boardTemperatureC = 37f,
            batteryTemperatureC = 38f,
            totalDistanceMetres = 12_345L,
            tripDistanceMetres = 678,
        )
        val ui = await("telemetry in uiState") { viewModel.uiState.value.takeIf { it.speedKmh != null } }

        assertEquals(listOf(ADDRESS to WheelFamily.G), repository.requests.toList())
        assertEquals(WheelFamily.G, viewModel.expectedFamily)
        assertEquals(ADDRESS, viewModel.address)
        assertEquals(ConnectionState.Ready, ui.connectionState)
        assertEquals("Test wheel", ui.identity?.modelName)
        assertEquals(12f, ui.speedKmh) // direction is dropped, magnitude kept
        assertEquals(80f, ui.voltageV)
        assertEquals(400f, ui.powerW)
        assertEquals(7f, ui.phaseCurrentA)
        assertEquals(40f, ui.pwmPercent)
        assertEquals(50f, ui.batteryPercent)
        assertEquals(79f, ui.batteryVoltageV)
        assertEquals(35f, ui.mosTemperatureC)
        assertEquals(36f, ui.motorTemperatureC)
        assertEquals(37f, ui.boardTemperatureC)
        assertEquals(38f, ui.batteryTemperatureC)
        assertEquals(12_345L, ui.totalDistanceMetres)
        assertEquals(678, ui.tripDistanceMetres)
        assertTrue("a frame without fault data means no faults", ui.faults?.isEmpty() == true)

        settingsFlow.value = AppSettings(useMetric = false, keepScreenOnDashboard = false)
        val imperial = await("settings in uiState") { viewModel.uiState.value.takeIf { !it.useMetric } }
        assertTrue(!imperial.keepScreenOnDashboard)
    }

    @Test
    fun anUnknownFamilyHintIsIgnoredAndAMissingAddressIsRejected() {
        val viewModel = create(FakeRepository { FakeConnection() }, handle(family = "NOT_A_FAMILY"))
        assertNull(viewModel.expectedFamily)

        assertThrows(IllegalArgumentException::class.java) {
            DashboardViewModel(
                FakeRepository { FakeConnection() },
                settings,
                noPacks,
                RuntimeEnvironment.getApplication(),
                SavedStateHandle(),
            )
        }
    }

    @Test
    fun aFailedConnectLeavesTheUiAtDisconnectedAndCommandsHarmless() {
        val repository = FakeRepository { throw IOException("adapter off") }
        val viewModel = create(repository, handle())
        collect(viewModel.uiState)
        await("the connect attempt") { repository.requests.firstOrNull() }

        assertEquals(DashboardUiState(), viewModel.uiState.value)
        viewModel.beep() // swallowed: the failure is logged, not thrown
        viewModel.setHeadlight(true)
        assertEquals(DashboardUiState().connectionState, viewModel.uiState.value.connectionState)
        assertTrue(viewModel.history.value.isEmpty())
        assertNull(viewModel.activeAlert.value)
    }

    // ---- Alerts -------------------------------------------------------

    @Test
    fun wheelAlertsAreBannerLoggedNewestFirstAndExpireAfterTheTtl() {
        val connection = FakeConnection()
        val viewModel = create(FakeRepository { connection }, handle())
        await("alert subscription") { connection.alerts.subscriptionCount.value.takeIf { it > 0 } }

        val first = WheelAlert.FallDown(timestampMillis = 1L)
        val second = WheelAlert.SpeedCutoff(timestampMillis = 2L, speedKmh = 30f)
        runBlocking { connection.alerts.emit(first) }
        assertEquals(DashboardAlert.Wheel(first), viewModel.activeAlert.value)

        scheduler.advanceTimeBy(DashboardViewModel.ALERT_TTL_MILLIS - 2_000L)
        runBlocking { connection.alerts.emit(second) }
        // The second alert restarts the clock: the first one's expiry must not clear it.
        scheduler.advanceTimeBy(DashboardViewModel.ALERT_TTL_MILLIS - 1_000L)
        assertEquals(DashboardAlert.Wheel(second), viewModel.activeAlert.value)
        scheduler.advanceTimeBy(1_001L)
        scheduler.runCurrent()
        assertNull(viewModel.activeAlert.value)

        assertEquals(
            listOf<DashboardAlert>(DashboardAlert.Wheel(second), DashboardAlert.Wheel(first)),
            viewModel.alertLog.value.map { it.alert },
        )
    }

    @Test
    fun theAlertLogKeepsOnlyTheNewestEntries() {
        val connection = FakeConnection()
        val viewModel = create(FakeRepository { connection }, handle())
        await("alert subscription") { connection.alerts.subscriptionCount.value.takeIf { it > 0 } }

        val total = DashboardViewModel.ALERT_LOG_LIMIT + 5
        runBlocking { repeat(total) { connection.alerts.emit(WheelAlert.FallDown(it.toLong())) } }

        val log = viewModel.alertLog.value
        assertEquals(DashboardViewModel.ALERT_LOG_LIMIT, log.size)
        assertEquals(WheelAlert.FallDown((total - 1).toLong()), (log.first().alert as DashboardAlert.Wheel).value)
    }

    @Test
    fun sustainedOverspeedRaisesAThresholdAlert() {
        val connection = FakeConnection()
        val viewModel = create(FakeRepository { connection }, handle())
        await("telemetry subscribers") { connection.telemetry.subscriptionCount.value.takeIf { it >= 2 } }

        // Default limit is 45 km/h and three consecutive samples over it are required.
        listOf(50f, 51f, 52f).forEachIndexed { index, speed ->
            connection.telemetry.value = WheelTelemetry(timestampMillis = 1_000L + index, speedKmh = speed)
        }

        val alert = viewModel.activeAlert.value as DashboardAlert.Threshold
        assertTrue(alert.value is ThresholdAlert.Overspeed)
    }

    // ---- History and ride metrics --------------------------------------

    @Test
    fun historySamplesAreCappedAndCarryPowerAndAbsoluteSpeed() {
        val connection = FakeConnection()
        val viewModel = create(FakeRepository { connection }, handle())
        await("telemetry subscribers") { connection.telemetry.subscriptionCount.value.takeIf { it >= 2 } }

        // The empty frame the connection starts with is sampled on subscription.
        assertEquals(1, viewModel.history.value.size)
        connection.telemetry.value = WheelTelemetry(timestampMillis = 1L, speedKmh = -3f, voltageV = 80f, currentA = 2f)
        val sample = viewModel.history.value.last()
        assertEquals(1L, sample.timestampMillis)
        assertEquals(3f, sample.speedKmh)
        assertEquals(160f, sample.powerW)

        // A frame lacking voltage or current has no power reading.
        connection.telemetry.value = WheelTelemetry(timestampMillis = 2L, speedKmh = 0f, voltageV = 80f)
        assertNull(viewModel.history.value.last().powerW)

        repeat(DashboardViewModel.HISTORY_LIMIT + 10) { i ->
            connection.telemetry.value = WheelTelemetry(timestampMillis = 10L + i, speedKmh = 0f, voltageV = i.toFloat())
        }
        assertEquals(DashboardViewModel.HISTORY_LIMIT, viewModel.history.value.size)
        assertEquals((DashboardViewModel.HISTORY_LIMIT + 9).toFloat(), viewModel.history.value.last().voltageV)
    }

    @Test
    fun rideMetricsStartOnFirstMovementAndWeighTheAverageByTime() {
        val connection = FakeConnection()
        val viewModel = create(FakeRepository { connection }, handle())
        collect(viewModel.uiState)
        await("telemetry subscribers") { connection.telemetry.subscriptionCount.value.takeIf { it >= 2 } }

        fun sample(at: Long, speed: Float?) {
            connection.telemetry.value = WheelTelemetry(timestampMillis = at, speedKmh = speed)
        }
        sample(1_000L, 0f) // parked: does not start the ride
        sample(2_000L, Float.NaN) // garbage counts as standing still
        assertNull(viewModel.uiState.value.avgSpeedKmh)
        sample(3_000L, 10f) // first riding sample anchors the average
        sample(5_000L, 20f) // 2 s at 20 km/h
        sample(7_000L, 0f) // 2 s standing: 40 km/h·s over 4 s

        val ui = await("ride metrics") { viewModel.uiState.value.takeIf { it.avgSpeedKmh == 10f } }
        assertEquals(20f, ui.maxSpeedKmh)
        assertEquals(10f, ui.avgSpeedKmh)

        // A repeated timestamp adds no interval and leaves the average alone.
        sample(7_000L, 5f)
        assertEquals(10f, viewModel.uiState.value.avgSpeedKmh)
    }

    @Test
    fun startsRecordingWhenTheRideBeginsOnAReadyWheel() {
        val connection = FakeConnection()
        create(FakeRepository { connection }, handle(family = "G"))
        await("telemetry subscribers") { connection.telemetry.subscriptionCount.value.takeIf { it >= 2 } }
        val app = shadowOf(RuntimeEnvironment.getApplication())

        connection.state.value = ConnectionState.Connecting
        connection.telemetry.value = WheelTelemetry(timestampMillis = 1_000L, speedKmh = 12f)
        assertNull("a wheel that is not Ready must not start a recording", app.peekNextStartedService())

        connection.state.value = ConnectionState.Ready
        connection.telemetry.value = WheelTelemetry(timestampMillis = 2_000L, speedKmh = 13f)
        assertNotNull(app.peekNextStartedService())
        val started = app.nextStartedService
        assertEquals("com.rideflux.app.recording.RecordingService", started.component?.className)
        assertEquals(ADDRESS, started.getStringExtra("address"))
        assertEquals("G", started.getStringExtra("family"))
    }

    // ---- Commands -----------------------------------------------------

    @Test
    fun helperCommandsReachTheConnectionAndHeadlightIsShownOptimistically() {
        val connection = FakeConnection()
        val viewModel = create(FakeRepository { connection }, handle())
        collect(viewModel.uiState)

        viewModel.setHeadlight(true)
        viewModel.setPedalsMode(2)
        viewModel.beep()
        until("three commands") { connection.dispatched.size == 3 }

        assertEquals(
            listOf(WheelCommand.SetHeadlight(true), WheelCommand.SetRideMode(2), WheelCommand.Beep),
            connection.dispatched.toList(),
        )
        assertTrue(viewModel.uiState.value.headlightOn)
        runBlocking {
            assertEquals(CommandOutcome.Success, viewModel.dispatch(WheelCommand.Horn))
        }
    }

    @Test
    fun dangerousCommandsGoThroughWhileStationary() {
        val connection = FakeConnection()
        val viewModel = create(FakeRepository { connection }, handle())
        collect(viewModel.uiState)
        connection.telemetry.value = WheelTelemetry(timestampMillis = 1_000L, speedKmh = 0f)
        await("stationary telemetry") { viewModel.uiState.value.takeIf { it.speedKmh == 0f } }

        viewModel.powerOff()
        viewModel.calibrate()
        viewModel.setMaxSpeedKmh(20f)
        until("three commands") { connection.dispatched.size == 3 }

        assertEquals(
            listOf(WheelCommand.PowerOff, WheelCommand.Calibrate, WheelCommand.SetMaxSpeedKmh(20f)),
            connection.dispatched.toList(),
        )
    }

    @Test
    fun dangerousCommandsAreRefusedWhileTheWheelIsMoving() {
        val connection = FakeConnection()
        val viewModel = create(FakeRepository { connection }, handle())
        collect(viewModel.uiState)
        connection.telemetry.value = WheelTelemetry(timestampMillis = 1_000L, speedKmh = 8f)
        await("moving telemetry") { viewModel.uiState.value.takeIf { it.speedKmh == 8f } }

        viewModel.powerOff()
        viewModel.calibrate()
        viewModel.setMaxSpeedKmh(20f)
        viewModel.beep() // harmless command as a barrier: anything sent before it has arrived by now
        until("the barrier command") { connection.dispatched.isNotEmpty() }

        assertEquals(listOf<WheelCommand>(WheelCommand.Beep), connection.dispatched.toList())
    }

    @Test
    fun aFailingDispatchIsSwallowedAndLaterCommandsStillWork() {
        val connection = FakeConnection()
        val viewModel = create(FakeRepository { connection }, handle())
        collect(viewModel.uiState)

        connection.dispatchError = IOException("link lost")
        viewModel.beep()
        until("the failed dispatch") { connection.failedDispatches.get() == 1 }

        connection.dispatchError = null
        viewModel.beep()
        until("the retried command") { connection.dispatched.size == 1 }
    }

    // ---- Teardown -----------------------------------------------------

    @Test
    fun clearingTheViewModelClosesTheResolvedConnectionOnce() {
        val connection = FakeConnection()
        val viewModel = create(FakeRepository { connection }, handle())
        collect(viewModel.uiState)
        await("a connected uiState") { viewModel.uiState.value.takeIf { it.connectionState == ConnectionState.Ready } }

        stores.getValue(viewModel).clear()

        until("the connection to close") { connection.closeCount.get() == 1 }
    }

    @Test
    fun clearingBeforeTheConnectFinishesStillClosesTheConnectionLater() {
        val connection = FakeConnection()
        val release = CompletableDeferred<Unit>()
        val repository = FakeRepository {
            release.await()
            connection
        }
        val viewModel = create(repository, handle())
        await("the connect attempt") { repository.requests.firstOrNull() }

        stores.getValue(viewModel).clear()
        assertEquals(0, connection.closeCount.get())
        release.complete(Unit)

        until("the late connection to close") { connection.closeCount.get() == 1 }
    }

    // ---- Helpers ------------------------------------------------------

    private fun handle(family: String? = null) = SavedStateHandle(
        buildMap {
            put(DashboardViewModel.ARG_ADDRESS, ADDRESS)
            if (family != null) put(DashboardViewModel.ARG_FAMILY, family)
        },
    )

    private fun create(repository: WheelRepository, handle: SavedStateHandle): DashboardViewModel {
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DashboardViewModel(repository, settings, noPacks, RuntimeEnvironment.getApplication(), handle) as T
            },
        )[DashboardViewModel::class.java]
        stores[viewModel] = store
        return viewModel
    }

    /** Keeps a `WhileSubscribed` state flow hot for the duration of the test. */
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

    private fun until(what: String, condition: () -> Boolean) {
        await(what) { if (condition()) true else null }
    }

    private class FakeRepository(private val onConnect: suspend () -> WheelConnection) : WheelRepository {
        val requests = CopyOnWriteArrayList<Pair<String, WheelFamily?>>()
        override fun scan() = flowOf(emptyList<DiscoveredWheel>())
        override fun activeConnections() = flowOf(emptyMap<String, WheelConnection>())
        override suspend fun connect(address: String, expectedFamily: WheelFamily?): WheelConnection {
            requests += address to expectedFamily
            return onConnect()
        }
    }

    private class FakeConnection : WheelConnection {
        val closeCount = AtomicInteger()
        val failedDispatches = AtomicInteger()
        val dispatched = CopyOnWriteArrayList<WheelCommand>()
        @Volatile var dispatchError: Throwable? = null

        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
        override val telemetry = MutableStateFlow(WheelTelemetry.EMPTY)
        override val identity = MutableStateFlow<WheelIdentity?>(null)
        override val capabilities = MutableStateFlow<WheelCapabilities?>(null)
        override val alerts = MutableSharedFlow<WheelAlert>(extraBufferCapacity = 256)
        override val speedKmh = MutableStateFlow<Float?>(null)
        override val voltageV = MutableStateFlow<Float?>(null)
        override val batteryPercent = MutableStateFlow<Float?>(null)
        override val currentA = MutableStateFlow<Float?>(null)
        override val mosTemperatureC = MutableStateFlow<Float?>(null)
        override val totalDistanceMetres = MutableStateFlow<Long?>(null)

        override suspend fun dispatch(command: WheelCommand): CommandOutcome {
            dispatchError?.let {
                failedDispatches.incrementAndGet()
                throw it
            }
            dispatched += command
            return CommandOutcome.Success
        }

        override suspend fun close() {
            closeCount.incrementAndGet()
        }
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
