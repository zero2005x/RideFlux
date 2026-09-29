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
import com.rideflux.data.bridge.SignalLevel
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelFamily
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
    private lateinit var service: BridgeService
    private lateinit var wheelRepository: WheelRepository

    @Before
    fun setUp() {
        service = BridgeService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(service, RuntimeEnvironment.getApplication())
        wheelRepository = mockk()
        service.wheelRepository = wheelRepository
        service.settingsRepository = mockk<SettingsRepository>(relaxed = true).also {
            every { it.settings } returns MutableStateFlow(AppSettings())
        }
        BridgeService.setHudVisible(true)
    }

    @After
    fun tearDown() {
        BridgeService.setHudVisible(true)
        service.onDestroy()
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

    @Suppress("UNCHECKED_CAST")
    private fun frames(): Flow<BridgeFrame> =
        BridgeService::class.java.getDeclaredMethod("frames").apply { isAccessible = true }
            .invoke(service) as Flow<BridgeFrame>

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
