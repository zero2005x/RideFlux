/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WheelTelemetryMergeTest {

    @Test
    fun nullMeansRetainAndInvalidationWins() {
        val base = WheelTelemetry(
            timestampMillis = 10L,
            speedKmh = 12f,
            pwmPercent = 40f,
            currentA = 3f,
        )
        val delta = WheelTelemetry(
            timestampMillis = 0L,
            pwmPercent = 13.7f,
            currentA = 9f,
        )
        val merged = WheelTelemetryMerge.merge(
            base = base,
            delta = delta,
            invalidated = setOf(TelemetryField.PWM, TelemetryField.CURRENT),
            fallbackTimestampMillis = 99L,
        )
        assertEquals(12f, merged.speedKmh)
        assertNull(merged.pwmPercent)
        assertNull(merged.currentA)
        assertEquals(10L, merged.timestampMillis)
    }

    @Test
    fun clockFallbackWhenBothTimestampsAreMissing() {
        val merged = WheelTelemetryMerge.merge(
            base = WheelTelemetry.EMPTY,
            delta = WheelTelemetry.EMPTY.copy(voltageV = 66.4f),
            fallbackTimestampMillis = 42L,
        )
        assertEquals(42L, merged.timestampMillis)
        assertEquals(66.4f, merged.voltageV)
    }
}
