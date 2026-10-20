/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.rideflux.data.bridge.BridgeFrame
import com.rideflux.data.bridge.DiagnosticLog
import com.rideflux.data.bridge.DiagnosticLogs
import com.rideflux.data.bridge.SignalLevel
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.repository.ScooterRepository
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.telemetry.ScooterTelemetry
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelFamily
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Drive the service's frame pipeline without starting its foreground publisher.
 * Attach an application context without calling onCreate(), which would start
 * Hilt injection. The private flow is invoked directly so the wheel and standby
 * transitions are exercised without advertising or foreground notifications.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BridgeServiceFramesTest {
    @get:Rule
    val diagnosticFolder = TemporaryFolder()

    private lateinit var service: BridgeService
    private lateinit var wheelRepository: WheelRepository
    private lateinit var scooterRepository: ScooterRepository

    @Before
    fun setUp() {
        DiagnosticLogs.install(DiagnosticLog(diagnosticFolder.newFolder("diagnostics")))
        service = BridgeService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(service, RuntimeEnvironment.getApplication())
        wheelRepository = mockk()
        service.wheelRepository = wheelRepository
        scooterRepository = mockk()
        every { scooterRepository.isDiscovered(any()) } returns false
        service.scooterRepository = scooterRepository
        service.settingsRepository = mockk<SettingsRepository>(relaxed = true).also {
            every { it.settings } returns MutableStateFlow(AppSettings())
        }
        BridgeService.setHudVisible(true)
    }

    @After
    fun tearDown() {
        BridgeService.setHudVisible(true)
        service.onDestroy()
        DiagnosticLogs.install(null)
    }

    @Test
    fun validatesTargetIntentAndEmitsHiddenStandbyFrame() = runBlocking {
        assertNull(readTarget(Intent().putExtra(BridgeService.EXTRA_MAC, "invalid")))
        val valid = readTarget(
            Intent()
                .putExtra(BridgeService.EXTRA_MAC, " aa:bb:cc:dd:ee:ff ")
                .putExtra(BridgeService.EXTRA_FAMILY, WheelFamily.K.name),
        )
        assertNotNull(valid)
        assertEquals("AA:BB:CC:DD:EE:FF", field(valid!!, "mac"))
        assertEquals(WheelFamily.K, field(valid, "family"))

        BridgeService.setHudVisible(false)
        val frame = withTimeout(5_000) { frames().first() }
        assertTrue(frame.hudHidden)
        assertFalse(frame.ready)
        assertEquals(BridgeState.STANDBY, BridgeService.state.value)
    }

    @Test
    fun readyWheelFrameIsRelayedAndConnectionClosesWhenCollectorStops() = runBlocking {
        val address = "AA:BB:CC:DD:EE:FF"
        val connection = mockk<WheelConnection>()
        every { connection.state } returns MutableStateFlow(ConnectionState.Ready)
        every { connection.telemetry } returns MutableStateFlow(
            WheelTelemetry(
                timestampMillis = System.currentTimeMillis(),
                speedKmh = -20f,
                batteryPercent = 75f,
                voltageV = 84f,
            ),
        )
        coEvery { connection.close() } returns Unit
        coEvery { wheelRepository.connect(address, WheelFamily.K) } returns connection
        val target = readTarget(
            Intent().putExtra(BridgeService.EXTRA_MAC, address)
                .putExtra(BridgeService.EXTRA_FAMILY, WheelFamily.K.name),
        )!!
        setTarget(target)

        val frame = withTimeout(5_000) { frames().first { it.ready } }
        assertEquals(20f, frame.speedKmh)
        assertEquals(75f, frame.vehicleBatteryPercent)
        assertEquals(SignalLevel.GOOD, frame.signal)
        assertEquals(BridgeState.RELAYING, BridgeService.state.value)
        coVerify(exactly = 1) { connection.close() }
    }

    @Test
    fun discoveredScooterIsRelayedThroughTheScooterRepositoryNotAsAWheel() = runBlocking {
        val address = "C7:B8:DC:3B:A1:B2"
        val scooter = mockk<ScooterConnection>()
        every { scooter.state } returns MutableStateFlow(ConnectionState.Ready)
        every { scooter.telemetry } returns MutableStateFlow(
            ScooterTelemetry(
                timestampMillis = System.currentTimeMillis(),
                speedKmh = 17.6f,
                batteryPercent = 60f,
                tripDistanceMetres = 5L,
            ),
        )
        coEvery { scooter.close() } returns Unit
        every { scooterRepository.isDiscovered(address) } returns true
        coEvery { scooterRepository.connect(address) } returns scooter
        setTarget(readTarget(Intent().putExtra(BridgeService.EXTRA_MAC, address))!!)

        val frame = withTimeout(5_000) { frames().first { it.ready } }
        assertEquals(17.6f, frame.speedKmh)
        assertEquals(60f, frame.vehicleBatteryPercent)
        assertEquals(5, frame.tripDistanceMetres)
        assertNull(frame.voltageV)
        assertEquals(BridgeState.RELAYING, BridgeService.state.value)
        coVerify(exactly = 0) { wheelRepository.connect(any(), any()) }
        coVerify(exactly = 1) { scooter.close() }
    }

    @Test
    fun failedWheelConnectionEmitsDegradedStandbyFrame() = runBlocking {
        val address = "11:22:33:44:55:66"
        coEvery { wheelRepository.connect(address, null) } throws IOException("wheel unavailable")
        setTarget(readTarget(Intent().putExtra(BridgeService.EXTRA_MAC, address))!!)

        val frame = withTimeout(5_000) { frames().first() }
        assertFalse(frame.ready)
        assertEquals(SignalLevel.NONE, frame.signal)
        assertEquals(BridgeState.DEGRADED, BridgeService.state.value)
    }

    @Test
    fun glassesConnectingAfreshShowAHudThatWasBlankedInAnEarlierSession() {
        BridgeService.setHudVisible(false)
        setLinkState(GlassesLinkState.READY)
        assertFalse("waiting for glasses keeps the choice", BridgeService.hudVisible.value)

        setLinkState(GlassesLinkState.CONNECTED)

        assertTrue(BridgeService.hudVisible.value)
    }

    @Test
    fun blankingTheHudDuringASessionSurvivesFurtherConnectedUpdatesAndALinkDrop() {
        setLinkState(GlassesLinkState.CONNECTED)
        BridgeService.setHudVisible(false)

        setLinkState(GlassesLinkState.CONNECTED)
        assertFalse("still the same session", BridgeService.hudVisible.value)

        setLinkState(GlassesLinkState.READY)
        assertFalse("a drop on its own does not change the choice", BridgeService.hudVisible.value)
    }

    @Test
    fun aFailingPipelineAnswersWithDegradedStandbyInsteadOfEndingTheStream() = runBlocking {
        // The very first read of the settings throws, which happens outside the wheel loop's own
        // try/catch, so it reaches the pipeline-level handler.
        every { service.settingsRepository.settings } throws IllegalStateException("boom") andThen
            MutableStateFlow(AppSettings())

        val frame = withTimeout(5_000) { frames().first() }

        assertFalse(frame.ready)
        assertTrue(frame.stale)
        assertEquals(BridgeState.DEGRADED, BridgeService.state.value)
    }

    @Test
    fun readyLinkWithoutTelemetryIsMarkedStaleAndDegraded() = runBlocking {
        val address = "22:33:44:55:66:77"
        val connection = mockk<WheelConnection>()
        every { connection.state } returns MutableStateFlow(ConnectionState.Ready)
        every { connection.telemetry } returns MutableStateFlow(WheelTelemetry.EMPTY)
        coEvery { connection.close() } returns Unit
        coEvery { wheelRepository.connect(address, null) } returns connection
        setTarget(readTarget(Intent().putExtra(BridgeService.EXTRA_MAC, address))!!)

        val frame = withTimeout(5_000) { frames().first() }
        assertTrue(frame.stale)
        assertFalse(frame.ready)
        assertEquals(SignalLevel.GOOD, frame.signal)
        assertEquals(BridgeState.DEGRADED, BridgeService.state.value)
        coVerify(exactly = 1) { connection.close() }
    }

    @Test
    fun telemetryStallAndRecoveryAreLoggedOnceWithoutClosingTheReadyLink() = runBlocking {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
        val telemetry = MutableStateFlow(WheelTelemetry(timestampMillis = System.currentTimeMillis(), speedKmh = 20f))
        val connection = connectWheel(state, telemetry)
        val received = Channel<BridgeFrame>(Channel.UNLIMITED)
        val collector = launch { frames().collect { received.send(it) } }
        try {
            withTimeout(5_000) { while (!received.receive().ready) Unit }
            assertEquals(BridgeState.RELAYING, BridgeService.state.value)

            telemetry.value = telemetry.value.copy(timestampMillis = telemetry.value.timestampMillis + 1, speedKmh = 21f)
            withTimeout(5_000) { while (received.receive().speedKmh != 21f) Unit }
            assertFalse(DiagnosticLogs.snapshot().contains("leaving RELAYING:"))

            // Removing the sample produces a stale snapshot immediately, without sleeping through
            // the watchdog interval or changing the connection state.
            telemetry.value = WheelTelemetry.EMPTY
            withTimeout(5_000) { while (!received.receive().stale) Unit }
            assertEquals(BridgeState.DEGRADED, BridgeService.state.value)
            val stalledLog = DiagnosticLogs.snapshot()
            assertTrue(stalledLog.contains("leaving RELAYING: conn=Ready ready=false stale=true frameAgeMs="))
            assertTrue(stalledLog.indexOf("leaving RELAYING:") < stalledLog.indexOf("state RELAYING -> DEGRADED"))
            coVerify(exactly = 0) { connection.close() }

            telemetry.value = WheelTelemetry(timestampMillis = System.currentTimeMillis(), speedKmh = 22f)
            withTimeout(5_000) { while (!received.receive().ready) Unit }
            assertEquals(BridgeState.RELAYING, BridgeService.state.value)
            assertEquals(1, DiagnosticLogs.snapshot().lineSequence().count { "leaving RELAYING:" in it })
        } finally {
            collector.cancel()
            collector.join()
            received.close()
        }
        coVerify(exactly = 1) { connection.close() }
    }

    @Test
    fun failedAndDisconnectedWheelLinksLogTheirLastStateAndReleaseTheConnection() = runBlocking {
        val terminalStates = listOf(
            ConnectionState.Failed(ConnectionState.Failed.Reason.BLE_LINK_LOST, "status 8") to "Failed(BLE_LINK_LOST: status 8)",
            ConnectionState.Disconnected to "Disconnected",
        )
        for ((terminal, expected) in terminalStates) {
            DiagnosticLogs.clear()
            val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
            val telemetry = MutableStateFlow(WheelTelemetry(timestampMillis = System.currentTimeMillis(), speedKmh = 20f))
            val connection = connectWheel(state, telemetry)
            val received = Channel<BridgeFrame>(Channel.UNLIMITED)
            val collector = launch { frames().collect { received.send(it) } }
            try {
                withTimeout(5_000) { while (!received.receive().ready) Unit }
                state.value = terminal
                // The standby frame follows the terminal snapshot and the loop's catch/finally,
                // so the last-state diagnostic and connection cleanup have both happened.
                withTimeout(5_000) { while (!received.receive().stale) Unit }
                assertEquals(BridgeState.DEGRADED, BridgeService.state.value)
                val log = DiagnosticLogs.snapshot()
                assertTrue(log.contains("leaving RELAYING: conn=$expected ready=false stale=false"))
                assertTrue(log.contains("wheel link **:**:**:**:EE:FF ended: WheelLinkEnded wheel link ended (last state $expected)"))
                assertFalse(log.contains("AA:BB:CC:DD:EE:FF"))
                coVerify(exactly = 1) { connection.close() }
            } finally {
                collector.cancel()
                collector.join()
                received.close()
            }
        }
    }

    @Test
    fun glassesLinkTransitionsAreLoggedOnceAndDoNotChangeWheelRelayingState() {
        setLinkState(GlassesLinkState.READY)
        DiagnosticLogs.clear()
        BridgeService::class.java.getDeclaredMethod("setBridgeState", BridgeState::class.java)
            .apply { isAccessible = true }
            .invoke(service, BridgeState.RELAYING)

        setLinkState(GlassesLinkState.CONNECTED)
        setLinkState(GlassesLinkState.CONNECTED)
        setLinkState(GlassesLinkState.READY)

        val transitions = DiagnosticLogs.snapshot().lineSequence().filter { "glasses link" in it }.toList()
        assertEquals(2, transitions.size)
        assertTrue(transitions[0].contains("glasses link READY -> CONNECTED"))
        assertTrue(transitions[1].contains("glasses link CONNECTED -> READY"))
        assertEquals(BridgeState.RELAYING, BridgeService.state.value)
    }

    private fun connectWheel(
        state: MutableStateFlow<ConnectionState>,
        telemetry: MutableStateFlow<WheelTelemetry>,
    ): WheelConnection {
        val address = "AA:BB:CC:DD:EE:FF"
        val connection = mockk<WheelConnection>()
        every { connection.state } returns state
        every { connection.telemetry } returns telemetry
        coEvery { connection.close() } returns Unit
        coEvery { wheelRepository.connect(address, null) } returns connection
        setTarget(readTarget(Intent().putExtra(BridgeService.EXTRA_MAC, address))!!)
        return connection
    }

    @Suppress("UNCHECKED_CAST")
    private fun frames(): Flow<BridgeFrame> =
        BridgeService::class.java.getDeclaredMethod("frames").apply { isAccessible = true }
            .invoke(service) as Flow<BridgeFrame>

    private fun setLinkState(state: GlassesLinkState) {
        BridgeService::class.java.getDeclaredMethod("setLinkState", GlassesLinkState::class.java)
            .apply { isAccessible = true }
            .invoke(service, state)
    }

    private fun readTarget(intent: Intent): Any? =
        BridgeService::class.java.getDeclaredMethod("readTarget", Intent::class.java)
            .apply { isAccessible = true }
            .invoke(service, intent)

    @Suppress("UNCHECKED_CAST")
    private fun setTarget(value: Any) {
        val target = BridgeService::class.java.getDeclaredField("target").apply { isAccessible = true }
            .get(service) as MutableStateFlow<Any?>
        target.value = value
    }

    private fun field(value: Any, name: String): Any? =
        value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
}
