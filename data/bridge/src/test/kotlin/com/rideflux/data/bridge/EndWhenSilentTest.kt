/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import com.rideflux.data.bridge.LinkSilenceWatchdog.Companion.NO_FRAME_AT_START_MILLIS
import com.rideflux.data.bridge.LinkSilenceWatchdog.Companion.SILENT_AFTER_STREAM_MILLIS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class EndWhenSilentTest {

    private val frame = BridgeFrame.EMPTY.copy(timestampMillis = 1_000L)

    private class Outcome {
        val frames = mutableListOf<BridgeFrame>()
        var error: Throwable? = null
        var completed = false
        fun message(): String = error?.message.orEmpty()
    }

    private fun TestScope.collect(flow: Flow<BridgeFrame>, outcome: Outcome): Job = backgroundScope.launch {
        try {
            flow.collect { outcome.frames += it }
            outcome.completed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            outcome.error = t
        }
    }

    private suspend fun TestScope.passMillis(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun framesPassThroughAndAQuietLinkFailsTheFlowAfterThirtySeconds() = runTest {
        val upstream = MutableSharedFlow<BridgeFrame>()
        val outcome = Outcome()
        collect(upstream.endWhenSilent(AtomicInteger(0)), outcome)
        runCurrent()

        upstream.emit(frame)
        runCurrent()
        assertEquals(listOf(frame), outcome.frames)

        passMillis(SILENT_AFTER_STREAM_MILLIS - 1_000)
        assertNull(outcome.error)
        passMillis(3_000)
        assertTrue(outcome.message(), outcome.message().contains("bridge link silent"))
    }

    @Test
    fun aFrameEveryFewSecondsKeepsTheFlowOpen() = runTest {
        val upstream = MutableSharedFlow<BridgeFrame>()
        val outcome = Outcome()
        collect(upstream.endWhenSilent(AtomicInteger(0)), outcome)
        runCurrent()

        repeat(24) {
            upstream.emit(frame)
            passMillis(5_000)
        }

        assertNull(outcome.error)
        assertEquals(24, outcome.frames.size)
    }

    @Test
    fun aFlowThatNeverDeliversFailsAfterTheApprovalWindowAndThenWaitsLonger() = runTest {
        val unanswered = AtomicInteger(0)
        val quiet = flow<BridgeFrame> { awaitCancellation() }

        val first = Outcome()
        collect(quiet.endWhenSilent(unanswered), first)
        runCurrent()
        passMillis(NO_FRAME_AT_START_MILLIS - 1_000)
        assertNull("the phone may still be waiting for the rider", first.error)
        passMillis(3_000)
        assertTrue(first.message(), first.message().contains("no frame"))
        assertEquals(1, unanswered.get())

        // The caller retries with the same counter and the second wait is twice as long.
        val second = Outcome()
        collect(quiet.endWhenSilent(unanswered), second)
        runCurrent()
        passMillis(2 * NO_FRAME_AT_START_MILLIS - 1_000)
        assertNull(second.error)
        passMillis(3_000)
        assertTrue(second.message(), second.message().contains("no frame"))
        assertEquals(2, unanswered.get())
    }

    @Test
    fun aFlowThatEndsOnItsOwnEndsWithoutWaitingForTheWatchdog() = runTest {
        val outcome = Outcome()

        collect(flowOf(frame, frame).endWhenSilent(AtomicInteger(0)), outcome)
        runCurrent()

        assertTrue(outcome.completed)
        assertEquals(2, outcome.frames.size)
    }

    @Test
    fun anUpstreamFailureIsPassedOnUnchanged() = runTest {
        val outcome = Outcome()
        val failing = flow<BridgeFrame> {
            emit(frame)
            throw IllegalStateException("link lost")
        }

        collect(failing.endWhenSilent(AtomicInteger(0)), outcome)
        runCurrent()

        assertEquals("link lost", outcome.message())
    }

    @Test
    fun cancellingTheCollectorStopsTheWatchdog() = runTest {
        val outcome = Outcome()
        val job = collect(flow<BridgeFrame> { awaitCancellation() }.endWhenSilent(AtomicInteger(0)), outcome)
        runCurrent()

        job.cancel()
        runCurrent()
        passMillis(2 * NO_FRAME_AT_START_MILLIS)

        assertNull(outcome.error)
        assertTrue(job.isCancelled)
    }
}
