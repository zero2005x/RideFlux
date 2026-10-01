/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.source

import android.app.Application
import com.rideflux.data.bridge.BridgeFrame
import com.rideflux.data.bridge.SignalLevel
import com.rideflux.hud.BridgeLinkState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A client link that stays open but stops delivering has to end up in the source's retry loop.
 *
 * Runs under Robolectric because the loop logs the lost link, and the plain unit-test android.jar
 * has no `android.util.Log`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BridgeTelemetrySourceSilentLinkTest {

    private val liveFrame = BridgeFrame.EMPTY.copy(
        timestampMillis = 123L,
        speedKmh = 18f,
        vehicleBatteryPercent = 75f,
        signal = SignalLevel.GOOD,
        stale = false,
        ready = true,
    )

    @Test
    fun linkThatStaysOpenButGoesQuietIsRestartedWithoutRestartingTheHud() = runTest {
        var starts = 0
        val source = BridgeTelemetrySource(
            clientFrames = {
                starts += 1
                flow {
                    emit(liveFrame)
                    awaitCancellation()
                }
            },
            testOnly = Unit,
        )
        val states = mutableListOf<HudTelemetryFrame>()
        backgroundScope.launch { source.frames().collect { states += it } }
        runCurrent()
        assertEquals(1, starts)
        assertEquals(BridgeLinkState.WHEEL_LIVE, states.last().bridgeLinkState)

        // The link stays open but nothing arrives any more: the display goes stale at once, and the
        // source starts a new attempt once the silence is long enough to call the link dead.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(BridgeLinkState.NO_PHONE, states.last().bridgeLinkState)
        assertEquals(1, starts)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, starts)
        assertEquals(BridgeLinkState.WHEEL_LIVE, states.last().bridgeLinkState)
    }

    @Test
    fun linkThatKeepsDeliveringIsLeftAlone() = runTest {
        var starts = 0
        val source = BridgeTelemetrySource(
            clientFrames = {
                starts += 1
                flow {
                    while (true) {
                        emit(liveFrame)
                        delay(1_000)
                    }
                }
            },
            testOnly = Unit,
        )
        val states = mutableListOf<HudTelemetryFrame>()
        backgroundScope.launch { source.frames().collect { states += it } }

        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(1, starts)
        assertEquals(BridgeLinkState.WHEEL_LIVE, states.last().bridgeLinkState)
    }
}
