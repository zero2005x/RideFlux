/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.recording

import com.rideflux.domain.telemetry.WheelTelemetry
import org.junit.Assert.assertEquals
import org.junit.Test

class TripStatisticsTest {
    @Test
    fun `integrates distance and moving duration`() {
        val accumulator = TripStatisticsAccumulator()
        accumulator.add(sample(36f), 0L)
        val result = accumulator.add(sample(36f), 5_000L)

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

    private fun sample(speed: Float) = WheelTelemetry(timestampMillis = 1L, speedKmh = speed)
}
