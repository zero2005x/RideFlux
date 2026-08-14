/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.alert

import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.telemetry.WheelTelemetry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThresholdStatusTrackerTest {
    @Test
    fun `activates after three samples and clears past hysteresis`() {
        val tracker = ThresholdStatusTracker()
        val limits = AlertThresholds(speedLimitKmh = 20f)
        assertFalse(tracker.update(sample(21f), limits))
        assertFalse(tracker.update(sample(22f), limits))
        assertTrue(tracker.update(sample(23f), limits))
        assertTrue(tracker.update(sample(19.5f), limits))
        assertFalse(tracker.update(sample(18f), limits))
    }

    private fun sample(speed: Float) = WheelTelemetry(timestampMillis = 1L, speedKmh = speed)
}
