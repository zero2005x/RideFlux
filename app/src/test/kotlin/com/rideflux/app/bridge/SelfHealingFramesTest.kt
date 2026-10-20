/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import com.rideflux.data.bridge.BridgeFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfHealingFramesTest {
    private fun frame(timestamp: Long) = standbyFrame(nowMillis = timestamp)

    private class Probe {
        val failures = mutableListOf<Throwable>()
        val waits = mutableListOf<Long>()
    }

    private fun TestScope.healing(probe: Probe, upstream: Flow<BridgeFrame>) = upstream.restartingOnFailure(
        backoffMillis = { attempt -> 1_000L * (attempt + 1) },
        healthyAfterMillis = 30_000L,
        elapsedRealtime = { testScheduler.currentTime },
        onFailure = { probe.failures += it },
        standbyFor = { wait ->
            probe.waits += wait
            emit(frame(wait))
            delay(wait)
        },
    )

    @Test
    fun aFailingPipelineIsRebuiltAndTheGlassesGetStandbyFramesMeanwhile() = runTest {
        var runs = 0
        val probe = Probe()
        val upstream = flow<BridgeFrame> {
            runs++
            if (runs < 3) throw IllegalStateException("boom $runs")
            emit(frame(100))
            awaitCancellation()
        }

        val seen = healing(probe, upstream).take(3).toList()

        // Each standby frame carries the wait it covers; the last one is the recovered pipeline's.
        assertEquals(listOf(1_000L, 2_000L, 100L), seen.map { it.timestampMillis })
        assertEquals(listOf("boom 1", "boom 2"), probe.failures.map { it.message })
        assertEquals(listOf(1_000L, 2_000L), probe.waits)
        assertEquals(3, runs)
    }

    @Test
    fun aRunThatStayedUpLongEnoughStartsTheBackoffOver() = runTest {
        var runs = 0
        val probe = Probe()
        val upstream = flow<BridgeFrame> {
            runs++
            when (runs) {
                1 -> throw IllegalStateException("early")
                2 -> {
                    delay(40_000L)
                    throw IllegalStateException("after a healthy run")
                }
            }
            emit(frame(1))
            awaitCancellation()
        }

        healing(probe, upstream).take(3).toList()

        // Without the reset the second wait would be 2 000 ms.
        assertEquals(listOf(1_000L, 1_000L), probe.waits)
    }

    @Test
    fun aRapidBurstOfFailuresKeepsBackingOff() = runTest {
        var runs = 0
        val probe = Probe()
        val upstream = flow<BridgeFrame> {
            runs++
            if (runs <= 4) throw IllegalStateException("again")
            emit(frame(1))
            awaitCancellation()
        }

        healing(probe, upstream).take(5).toList()

        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), probe.waits)
    }

    @Test
    fun cancellationPassesThroughAndIsNotRetried() = runTest {
        val probe = Probe()
        val upstream = flow<BridgeFrame> { throw CancellationException("stop") }

        val thrown = runCatching { healing(probe, upstream).collect { } }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertTrue(probe.failures.isEmpty())
        assertTrue(probe.waits.isEmpty())
    }

    @Test
    fun anExceptionFromTheCollectorIsNotThePipelinesToCatch() = runTest {
        var runs = 0
        val probe = Probe()
        val upstream = flow<BridgeFrame> {
            runs++
            emit(frame(1))
            awaitCancellation()
        }

        val thrown = runCatching {
            healing(probe, upstream).collect { throw IllegalStateException("collector went away") }
        }.exceptionOrNull()

        assertEquals("collector went away", thrown?.message)
        assertEquals(1, runs)
        assertTrue(probe.failures.isEmpty())
    }
}
