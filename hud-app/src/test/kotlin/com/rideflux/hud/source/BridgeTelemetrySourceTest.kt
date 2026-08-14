/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.source

import com.rideflux.data.bridge.BridgeFrame
import com.rideflux.data.bridge.SignalLevel
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.hud.BridgeLinkState
import com.rideflux.hud.HudViewModel
import com.rideflux.hud.resolveHudSourceKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeTelemetrySourceTest {
    @Test
    fun launcherDefaultsToBridge_unlessDirectIsExplicit() {
        assertEquals(HudViewModel.SOURCE_BRIDGE, resolveHudSourceKind(null))
        assertEquals(HudViewModel.SOURCE_BRIDGE, resolveHudSourceKind("unknown"))
        assertEquals(HudViewModel.SOURCE_DIRECT, resolveHudSourceKind(" direct "))
    }

    @Test
    fun idleBridgeFrame_meansPhoneStandby() {
        val result = bridgeFrame(ready = false, stale = true, signal = SignalLevel.NONE)
            .toHudTelemetryFrame()

        assertEquals(BridgeLinkState.PHONE_STANDBY, result.bridgeLinkState)
        assertEquals(ConnectionState.Connecting, result.state)
    }

    @Test
    fun freshReadyFrame_meansWheelLive() {
        val result = bridgeFrame(ready = true, stale = false, signal = SignalLevel.GOOD)
            .toHudTelemetryFrame()

        assertEquals(BridgeLinkState.WHEEL_LIVE, result.bridgeLinkState)
        assertEquals(ConnectionState.Ready, result.state)
    }

    @Test
    fun bridgeFrame_preservesPhoneBatteryForHud() {
        val result = bridgeFrame(
            ready = false,
            stale = true,
            signal = SignalLevel.NONE,
            phoneBatteryPercent = 63,
        ).toHudTelemetryFrame()

        assertEquals(63, result.phoneBatteryPercent)
    }

    @Test
    fun completedClientFlow_reconnectsInsteadOfTerminating() = runTest {
        var connections = 0
        val source = BridgeTelemetrySource(
            clientFrames = {
                connections += 1
                flowOf(bridgeFrame(ready = false, stale = true, signal = SignalLevel.NONE))
            },
            testOnly = Unit,
        )

        val states = source.frames().take(4).toList().map { it.bridgeLinkState }

        assertEquals(
            listOf(
                BridgeLinkState.NO_PHONE,
                BridgeLinkState.PHONE_STANDBY,
                BridgeLinkState.NO_PHONE,
                BridgeLinkState.PHONE_STANDBY,
            ),
            states,
        )
        assertEquals(2, connections)
    }

    @Test
    fun cxrStartupGrace_delaysNativeBleConnection() = runTest {
        var nativeConnections = 0
        val cxrFrames = MutableSharedFlow<BridgeFrame>()
        val source = BridgeTelemetrySource(
            clientFrames = {
                nativeConnections += 1
                flow { awaitCancellation() }
            },
            rokidFrames = { cxrFrames },
            testOnly = Unit,
        )
        backgroundScope.launch { source.frames().collect() }

        runCurrent()
        advanceTimeBy(2_999L)
        runCurrent()
        assertEquals(0, nativeConnections)

        advanceTimeBy(2L)
        runCurrent()
        assertEquals(1, nativeConnections)
    }

    @Test
    fun cxrFrame_cancelsNativeBleUntilFreshnessExpires() = runTest {
        var nativeConnections = 0
        var nativeCancellations = 0
        val cxrFrames = MutableSharedFlow<BridgeFrame>()
        val source = BridgeTelemetrySource(
            clientFrames = {
                nativeConnections += 1
                flow {
                    try {
                        awaitCancellation()
                    } finally {
                        nativeCancellations += 1
                    }
                }
            },
            rokidFrames = { cxrFrames },
            testOnly = Unit,
        )
        backgroundScope.launch { source.frames().collect() }

        runCurrent()
        advanceTimeBy(3_001L)
        runCurrent()
        assertEquals(1, nativeConnections)

        cxrFrames.emit(bridgeFrame(ready = true, stale = false, signal = SignalLevel.GOOD))
        runCurrent()
        assertEquals(1, nativeCancellations)

        advanceTimeBy(2_499L)
        runCurrent()
        assertEquals(1, nativeConnections)

        advanceTimeBy(2L)
        runCurrent()
        assertEquals(2, nativeConnections)
    }

    private fun bridgeFrame(
        ready: Boolean,
        stale: Boolean,
        signal: SignalLevel,
        phoneBatteryPercent: Int? = null,
    ) = BridgeFrame(
        timestampMillis = 123L,
        speedKmh = if (ready) 18f else null,
        vehicleBatteryPercent = if (ready) 75f else null,
        phoneBatteryPercent = phoneBatteryPercent,
        voltageV = null,
        tripDistanceMetres = null,
        tripDurationSeconds = null,
        signal = signal,
        stale = stale,
        ready = ready,
    )
}
