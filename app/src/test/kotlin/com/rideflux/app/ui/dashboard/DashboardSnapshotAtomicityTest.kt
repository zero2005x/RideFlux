/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.connection.WheelSessionSnapshot
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import com.rideflux.domain.wheel.WheelBatteryPackStore
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelControlContract
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity
import com.rideflux.domain.wheel.WheelIdentityEvidence
import com.rideflux.domain.wheel.WheelSupport
import com.rideflux.domain.wheel.WheelTelemetryCoverage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The dashboard's identity, capabilities and support are one publication.
 *
 * A connection that publishes `sessionSnapshot` must have those three fields
 * come from the same snapshot; the legacy `identity` / `capabilities` /
 * `support` flows may keep emitting (they are the compatibility surface) but
 * must not reach the UI once a snapshot exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardSnapshotAtomicityTest {

    private val scheduler = TestCoroutineScheduler()
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val stores = mutableListOf<ViewModelStore>()
    private val settingsFlow = MutableStateFlow(AppSettings())
    private val settings = mockk<SettingsRepository>()

    private val noPacks = object : WheelBatteryPackStore {
        override val seriesCells = MutableStateFlow<Map<String, Int>>(emptyMap())
        override suspend fun setSeriesCells(address: String, cells: Int?) = Unit
    }

    private val address = "AA:BB:CC:DD:EE:FF"

    @Before
    fun setUp() {
        every { settings.settings } returns settingsFlow
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        shadowOf(RuntimeEnvironment.getApplication()).clearStartedServices()
    }

    @After
    fun tearDown() {
        collectors.cancel()
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    // ---- 1. Legacy flows cannot contaminate the snapshot ------------------

    @Test
    fun `legacy identity, capabilities and support cannot overwrite a snapshot`() {
        val connection = SnapshotConnection()
        val viewModel = viewModel(connection)
        val publication = publication("Snapshot wheel", headlight = true)

        connection.sessionSnapshot.value = WheelSessionSnapshot(
            sequence = 1,
            identity = publication.identity,
            capabilities = publication.capabilities,
            support = publication.support,
        )
        await("the snapshot publication") {
            viewModel.uiState.value.identity?.modelName?.takeIf { it == "Snapshot wheel" }
        }

        // The compatibility flows carry a different publication. They must not
        // reach the UI while a snapshot exists.
        connection.identity.value = identity("Legacy wheel")
        connection.capabilities.value = caps(headlight = false)
        connection.support.value = support(WheelIdentityEvidence.FAMILY_ONLY)

        settle()

        assertEquals("Snapshot wheel", viewModel.uiState.value.identity?.modelName)
        assertSame(publication.capabilities, viewModel.uiState.value.capabilities)
        assertSame(publication.support, viewModel.uiState.value.support)
    }

    // ---- 2. Consecutive snapshots stay internally consistent --------------

    @Test
    fun `two snapshots each render their own identity, capabilities and support`() {
        val connection = SnapshotConnection()
        val viewModel = viewModel(connection)
        val first = publication("First wheel", headlight = true)
        val second = publication("Second wheel", headlight = false)

        connection.sessionSnapshot.value = snapshot(1, first)
        await("the first publication") {
            viewModel.uiState.value.identity?.modelName?.takeIf { it == "First wheel" }
        }
        assertSame(first.capabilities, viewModel.uiState.value.capabilities)
        assertSame(first.support, viewModel.uiState.value.support)

        connection.sessionSnapshot.value = snapshot(2, second)
        await("the second publication") {
            viewModel.uiState.value.identity?.modelName?.takeIf { it == "Second wheel" }
        }
        assertSame(second.capabilities, viewModel.uiState.value.capabilities)
        assertSame(second.support, viewModel.uiState.value.support)
    }

    // ---- 3. Stale and duplicate sequences are ignored ---------------------

    @Test
    fun `an older sequence cannot replace the accepted publication`() {
        val connection = SnapshotConnection()
        val viewModel = viewModel(connection)
        val newer = publication("Newer wheel", headlight = true)
        val older = publication("Older wheel", headlight = false)

        connection.sessionSnapshot.value = snapshot(5, newer)
        await("the newer publication") {
            viewModel.uiState.value.identity?.modelName?.takeIf { it == "Newer wheel" }
        }

        connection.sessionSnapshot.value = snapshot(4, older)
        settle()

        assertEquals("Newer wheel", viewModel.uiState.value.identity?.modelName)
        assertSame(newer.capabilities, viewModel.uiState.value.capabilities)
    }

    @Test
    fun `a repeated sequence cannot replace the accepted publication`() {
        val connection = SnapshotConnection()
        val viewModel = viewModel(connection)
        val accepted = publication("Accepted wheel", headlight = true)
        val duplicate = publication("Duplicate wheel", headlight = false)

        connection.sessionSnapshot.value = snapshot(3, accepted)
        await("the accepted publication") {
            viewModel.uiState.value.identity?.modelName?.takeIf { it == "Accepted wheel" }
        }

        connection.sessionSnapshot.value = snapshot(3, duplicate)
        settle()

        assertEquals("Accepted wheel", viewModel.uiState.value.identity?.modelName)
        assertSame(accepted.support, viewModel.uiState.value.support)
    }

    // ---- 4. The connection instance is the sequence boundary --------------

    @Test
    fun `a new connection session may start from a lower sequence`() {
        val firstConnection = SnapshotConnection()
        val first = viewModel(firstConnection)
        firstConnection.sessionSnapshot.value = snapshot(9, publication("Session one", headlight = true))
        await("session one") {
            first.uiState.value.identity?.modelName?.takeIf { it == "Session one" }
        }

        // A replacement connection is a new session: sequence 0 is legitimate
        // even though the previous session reached 9.
        val secondConnection = SnapshotConnection()
        val second = viewModel(secondConnection)
        secondConnection.sessionSnapshot.value =
            snapshot(0, publication("Session two", headlight = false))

        await("session two") {
            second.uiState.value.identity?.modelName?.takeIf { it == "Session two" }
        }

        assertEquals("Session two", second.uiState.value.identity?.modelName)
        assertEquals("Session one", first.uiState.value.identity?.modelName)
    }

    // ---- 5. Teardown and failure leave nothing behind ---------------------

    @Test
    fun `a cleared snapshot drops the identity and the controls`() {
        val connection = SnapshotConnection()
        val viewModel = viewModel(connection)
        val publication = publication("Live wheel", headlight = true)

        // A real connection also writes the legacy flows at Identified; they
        // stay populated after teardown, so the UI must not read them.
        connection.identity.value = publication.identity
        connection.capabilities.value = publication.capabilities
        connection.support.value = publication.support
        connection.sessionSnapshot.value = snapshot(1, publication)
        await("the live publication") {
            viewModel.uiState.value.identity?.modelName?.takeIf { it == "Live wheel" }
        }

        connection.sessionSnapshot.value = null
        await("the cleared identity") {
            if (viewModel.uiState.value.identity == null) true else null
        }

        assertNull("capabilities must not survive teardown", viewModel.uiState.value.capabilities)
        assertNull("support must not survive teardown", viewModel.uiState.value.support)
    }

    @Test
    fun `a failed session shows neither identity nor controls`() {
        val connection = SnapshotConnection()
        val viewModel = viewModel(connection)
        val publication = publication("Failed wheel", headlight = true)

        connection.identity.value = publication.identity
        connection.capabilities.value = publication.capabilities
        connection.sessionSnapshot.value = snapshot(2, publication)
        await("the publication") {
            viewModel.uiState.value.identity?.modelName?.takeIf { it == "Failed wheel" }
        }

        connection.state.value = ConnectionState.Failed(
            ConnectionState.Failed.Reason.BLE_LINK_LOST,
            "link dropped",
        )
        connection.sessionSnapshot.value = null

        await("the failed state") {
            if (viewModel.uiState.value.connectionState is ConnectionState.Failed) true else null
        }
        assertNull(viewModel.uiState.value.identity)
        assertNull(viewModel.uiState.value.capabilities)
    }

    @Test
    fun `a late snapshot after teardown cannot resurrect the session`() {
        val connection = SnapshotConnection()
        val viewModel = viewModel(connection)
        val publication = publication("Ended wheel", headlight = true)

        connection.sessionSnapshot.value = snapshot(1, publication)
        await("the publication") {
            viewModel.uiState.value.identity?.modelName?.takeIf { it == "Ended wheel" }
        }

        connection.sessionSnapshot.value = null
        await("the cleared identity") {
            if (viewModel.uiState.value.identity == null) true else null
        }

        // A snapshot that arrives after the session was retired belongs to no
        // live session; showing it would put a dead wheel back on the screen.
        connection.sessionSnapshot.value = snapshot(2, publication("Ghost wheel", headlight = false))
        settle()

        assertNull(viewModel.uiState.value.identity)
        assertNull(viewModel.uiState.value.capabilities)
    }

    // ---- Harness ----------------------------------------------------------

    private data class Publication(
        val identity: WheelIdentity,
        val capabilities: WheelCapabilities,
        val support: WheelSupport,
    )

    private fun identity(model: String) =
        WheelIdentity(address = address, family = WheelFamily.G, modelName = model)

    private fun caps(headlight: Boolean) = WheelCapabilities(
        headlight = headlight,
        horn = false,
        beep = false,
        ledStrip = false,
        decorativeLights = false,
        rideModes = false,
        maxSpeed = false,
        tiltback = false,
        pedalSensitivity = false,
        pedalHorizontal = false,
        calibration = false,
        powerOff = false,
        volume = false,
        playSound = false,
        pinUnlock = false,
        asyncAlerts = false,
    )

    private fun support(evidence: WheelIdentityEvidence) = WheelSupport(
        identityEvidence = evidence,
        telemetryCoverage = WheelTelemetryCoverage.MODEL_LAYOUT,
        controlContract = WheelControlContract.FAMILY_CLOSED,
    )

    private fun publication(model: String, headlight: Boolean) = Publication(
        identity = identity(model),
        capabilities = caps(headlight),
        support = support(WheelIdentityEvidence.MODEL_AND_FIRMWARE),
    )

    private fun snapshot(sequence: Long, publication: Publication) = WheelSessionSnapshot(
        sequence = sequence,
        identity = publication.identity,
        capabilities = publication.capabilities,
        support = publication.support,
    )

    private fun handle() = SavedStateHandle(
        buildMap {
            put(DashboardViewModel.ARG_ADDRESS, address)
            put(DashboardViewModel.ARG_FAMILY, "G")
        },
    )

    private fun viewModel(connection: WheelConnection): DashboardViewModel {
        val store = ViewModelStore()
        stores += store
        val repository = object : WheelRepository {
            override fun scan() = flowOf(emptyList<DiscoveredWheel>())
            override fun activeConnections() = flowOf(emptyMap<String, WheelConnection>())
            override suspend fun connect(address: String, expectedFamily: WheelFamily?) = connection
        }
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DashboardViewModel(
                        repository,
                        settings,
                        noPacks,
                        ApplicationProvider.getApplicationContext<Context>(),
                        handle(),
                    ) as T
            },
        )[DashboardViewModel::class.java]
        collectors.launch { viewModel.uiState.collect { } }
        return viewModel
    }

    private fun settle() {
        repeat(5) {
            scheduler.runCurrent()
            Thread.sleep(10)
        }
        scheduler.runCurrent()
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

    private class SnapshotConnection : WheelConnection {
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
        override val telemetry = MutableStateFlow(WheelTelemetry.EMPTY)
        override val identity = MutableStateFlow<WheelIdentity?>(null)
        override val capabilities = MutableStateFlow<WheelCapabilities?>(null)
        override val support = MutableStateFlow<WheelSupport?>(null)
        override val sessionSnapshot = MutableStateFlow<WheelSessionSnapshot?>(null)
        override val alerts = MutableSharedFlow<WheelAlert>(extraBufferCapacity = 16)
        override val speedKmh = MutableStateFlow<Float?>(null)
        override val voltageV = MutableStateFlow<Float?>(null)
        override val batteryPercent = MutableStateFlow<Float?>(null)
        override val currentA = MutableStateFlow<Float?>(null)
        override val mosTemperatureC = MutableStateFlow<Float?>(null)
        override val totalDistanceMetres = MutableStateFlow<Long?>(null)

        override suspend fun dispatch(command: WheelCommand): CommandOutcome = CommandOutcome.Success

        override suspend fun close() = Unit
    }
}
