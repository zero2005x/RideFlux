/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `batteryPercent` invariant is the domain's only hard guard against
 * a bad frame reaching safety logic and the UI, and `copy()` has to be
 * covered as well as the constructor — a snapshot is almost always built
 * by copying the previous one.
 */
class WheelTelemetryTest {

    @Test
    fun `accepts the full legal battery range`() {
        for (percent in listOf(0f, 0.1f, 50f, 99.9f, 100f)) {
            val telemetry = WheelTelemetry(timestampMillis = 1L, batteryPercent = percent)
            assertEquals(percent, telemetry.batteryPercent)
        }
    }

    @Test
    fun `accepts a null battery percent as unknown`() {
        assertNull(WheelTelemetry(timestampMillis = 1L).batteryPercent)
    }

    @Test
    fun `rejects a battery percent below zero`() {
        assertThrows(IllegalArgumentException::class.java) {
            WheelTelemetry(timestampMillis = 1L, batteryPercent = -0.5f)
        }
    }

    @Test
    fun `rejects a battery percent above one hundred`() {
        assertThrows(IllegalArgumentException::class.java) {
            WheelTelemetry(timestampMillis = 1L, batteryPercent = 100.5f)
        }
    }

    @Test
    fun `rejects non-finite battery percents`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                WheelTelemetry(timestampMillis = 1L, batteryPercent = bad)
            }
        }
    }

    @Test
    fun `copy is checked as strictly as the constructor`() {
        val valid = WheelTelemetry(timestampMillis = 1L, batteryPercent = 50f)
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(batteryPercent = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(batteryPercent = 101f)
        }
    }

    @Test
    fun `failure message names the offending value`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WheelTelemetry(timestampMillis = 1L, batteryPercent = 250f)
        }
        assertTrue(
            "Message should quote the rejected value, was: ${error.message}",
            error.message.orEmpty().contains("250"),
        )
    }

    @Test
    fun `EMPTY carries the no-data timestamp sentinel and no readings`() {
        val empty = WheelTelemetry.EMPTY
        assertEquals(0L, empty.timestampMillis)
        assertNull(empty.speedKmh)
        assertNull(empty.batteryPercent)
        assertNull(empty.voltageV)
        // null faults means "not reported yet", which callers must be able
        // to tell apart from a confirmed-healthy empty set.
        assertNull(empty.faults)
    }

    @Test
    fun `an empty fault set is distinct from an unreported one`() {
        val unreported = WheelTelemetry(timestampMillis = 1L, faults = null)
        val healthy = WheelTelemetry(timestampMillis = 1L, faults = emptySet())
        assertNull(unreported.faults)
        assertNotNull(healthy.faults)
        assertTrue(healthy.faults!!.isEmpty())
    }

    @Test
    fun `unknown faults keep the raw bit for diagnostics`() {
        val fault = WheelFault.Unknown(domain = "familyk", code = 12)
        val telemetry = WheelTelemetry(timestampMillis = 1L, faults = setOf(fault))
        assertEquals(setOf<WheelFault>(WheelFault.Unknown("familyk", 12)), telemetry.faults)
    }

    @Test
    fun `families without a ride-mode concept use the sentinel code`() {
        assertEquals(-1, RideMode(code = -1, label = "").code)
    }

    /**
     * Every other physical field is deliberately unconstrained: a wheel
     * really can report a negative current (regen) or a negative speed
     * (reverse), and clamping here would hide a genuine reading.
     */
    @Test
    fun `other physical fields accept negative readings`() {
        val telemetry = WheelTelemetry(
            timestampMillis = 1L,
            speedKmh = -3.5f,
            currentA = -20f,
            mosTemperatureC = -10f,
        )
        assertEquals(-3.5f, telemetry.speedKmh)
        assertEquals(-20f, telemetry.currentA)
        assertEquals(-10f, telemetry.mosTemperatureC)
    }
}
