/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.recording

import com.rideflux.domain.telemetry.WheelTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripStatisticsTest {
    @Test
    fun `integrates distance and moving duration`() {
        val accumulator = TripStatisticsAccumulator()
        accumulator.add(sample(36f), 0L)
        var result = accumulator.snapshot()
        for (second in 1..5) result = accumulator.add(sample(36f), second * 1_000L)

        assertEquals(50.0, result.distanceMetres, 0.001)
        assertEquals(5L, result.durationSeconds)
        assertEquals(36f, result.maxSpeedKmh ?: -1f, 0.001f)
        assertEquals(36f, result.avgSpeedKmh ?: -1f, 0.001f)
    }

    @Test
    fun `stationary time affects average but not riding duration`() {
        val accumulator = TripStatisticsAccumulator()
        accumulator.add(sample(20f), 0L)
        accumulator.add(sample(20f), 1_000L)
        val result = accumulator.add(sample(0f), 2_000L)

        assertEquals(1L, result.durationSeconds)
        assertEquals(10f, result.avgSpeedKmh ?: -1f, 0.001f)
    }

    @Test
    fun `a twelve second gap is not capped and counted as travelled distance`() {
        val accumulator = TripStatisticsAccumulator()
        accumulator.add(sample(36f), 0L)
        accumulator.add(sample(36f), 1_000L)
        val afterGap = accumulator.add(sample(13.32f), 13_000L)
        assertEquals(10.0, afterGap.distanceMetres, 0.001)
        assertEquals(1L, afterGap.durationSeconds)
        assertEquals(36f, afterGap.avgSpeedKmh!!, 0.001f)
        val resumed = accumulator.add(sample(36f), 14_000L)
        assertEquals(20.0, resumed.distanceMetres, 0.001)
    }

    @Test
    fun `unknown speed is not stationary and does not bridge the next interval`() {
        val accumulator = TripStatisticsAccumulator()
        assertNull(accumulator.add(WheelTelemetry.EMPTY, 0L).maxSpeedKmh)
        accumulator.add(sample(36f), 1_000L)
        accumulator.add(sample(36f), 2_000L)
        val unknown = accumulator.add(WheelTelemetry.EMPTY, 3_000L)
        assertEquals(36f, unknown.avgSpeedKmh!!, 0.001f)
        assertEquals(10.0, accumulator.add(sample(36f), 4_000L).distanceMetres, 0.001)
        assertEquals(20.0, accumulator.add(sample(36f), 5_000L).distanceMetres, 0.001)
    }

    private fun sample(speed: Float) = WheelTelemetry(timestampMillis = 1L, speedKmh = speed)
}
