/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import com.rideflux.data.bridge.LinkSilenceWatchdog.Companion.NO_FRAME_AT_START_MILLIS
import com.rideflux.data.bridge.LinkSilenceWatchdog.Companion.NO_FRAME_MAX_MILLIS
import com.rideflux.data.bridge.LinkSilenceWatchdog.Companion.SILENT_AFTER_STREAM_MILLIS
import com.rideflux.data.bridge.LinkSilenceWatchdog.Companion.firstFrameLimitMillis
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class LinkSilenceWatchdogTest {

    private fun TestScope.watch(watchdog: LinkSilenceWatchdog): Deferred<String> =
        backgroundScope.async { watchdog.awaitSilence() }

    private suspend fun TestScope.passMillis(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun aLinkThatWasStreamingIsDeadAfterThirtySecondsOfSilence() = runTest {
        val watchdog = LinkSilenceWatchdog(AtomicInteger(0))
        val verdict = watch(watchdog)
        watchdog.onFrame()

        // The frame is noticed at the first poll (1 s); the silence counts from the next one.
        passMillis(SILENT_AFTER_STREAM_MILLIS)
        assertFalse(verdict.isCompleted)
        passMillis(1_001)
        assertTrue(verdict.isCompleted)
        assertEquals("bridge link silent for 30000ms", verdict.await())
    }

    @Test
    fun aFrameEveryFewSecondsKeepsTheLinkAlive() = runTest {
        val watchdog = LinkSilenceWatchdog(AtomicInteger(0))
        val verdict = watch(watchdog)

        repeat(24) {
            watchdog.onFrame()
            passMillis(5_000)
        }

        assertFalse(verdict.isCompleted)
    }

    @Test
    fun aFewQuietSecondsAreNotEnoughToCallALiveLinkDead() = runTest {
        // A phone with its screen off can sleep between notifications for a while.
        val watchdog = LinkSilenceWatchdog(AtomicInteger(0))
        val verdict = watch(watchdog)

        repeat(6) {
            watchdog.onFrame()
            passMillis(20_000)
        }

        assertFalse(verdict.isCompleted)
    }

    @Test
    fun aFirstFrameIsAwaitedForTheApprovalWindowAndTheTimeToConnect() = runTest {
        val unanswered = AtomicInteger(0)
        val verdict = watch(LinkSilenceWatchdog(unanswered))

        passMillis(NO_FRAME_AT_START_MILLIS - 1_000)
        assertFalse("the phone may still be waiting for the rider", verdict.isCompleted)
        assertEquals(0, unanswered.get())

        passMillis(1_001)
        assertTrue(verdict.isCompleted)
        assertEquals("bridge delivered no frame in 100000ms", verdict.await())
        assertEquals(1, unanswered.get())
    }

    @Test
    fun eachUnansweredAttemptDoublesTheWait() = runTest {
        val verdict = watch(LinkSilenceWatchdog(AtomicInteger(1)))

        passMillis(2 * NO_FRAME_AT_START_MILLIS - 1_000)
        assertFalse(verdict.isCompleted)
        passMillis(1_001)
        assertTrue(verdict.isCompleted)
    }

    @Test
    fun theWaitStopsGrowingAtTenMinutes() = runTest {
        val verdict = watch(LinkSilenceWatchdog(AtomicInteger(9)))

        passMillis(NO_FRAME_MAX_MILLIS - 1_000)
        assertFalse(verdict.isCompleted)
        passMillis(1_001)
        assertTrue(verdict.isCompleted)
    }

    @Test
    fun theFirstFrameClearsTheUnansweredCount() = runTest {
        val unanswered = AtomicInteger(3)
        val watchdog = LinkSilenceWatchdog(unanswered)
        watch(watchdog)

        watchdog.onFrame()
        passMillis(1_001)

        assertEquals(0, unanswered.get())
    }

    @Test
    fun theWaitForAFirstFrameGrowsToACapAndNeverBelowTheBase() {
        assertEquals(100_000L, firstFrameLimitMillis(0))
        assertEquals(200_000L, firstFrameLimitMillis(1))
        assertEquals(400_000L, firstFrameLimitMillis(2))
        assertEquals(600_000L, firstFrameLimitMillis(3))
        assertEquals(600_000L, firstFrameLimitMillis(1_000))
        assertEquals(100_000L, firstFrameLimitMillis(-5))
    }

    @Test
    fun theFirstFrameWaitOutlastsTheApprovalWindowOnThePhone() {
        // A request that lapsed on the phone must be asked again, so the glasses wait longer than the
        // phone does, by more than a scan, a connect and a subscribe can take.
        assertTrue(
            NO_FRAME_AT_START_MILLIS >=
                BridgeProtocol.PENDING_AUTHORIZATION_TIMEOUT_MILLIS + LinkSilenceWatchdog.CONNECT_ALLOWANCE_MILLIS,
        )
        assertTrue(LinkSilenceWatchdog.CONNECT_ALLOWANCE_MILLIS > 0)
    }
}
