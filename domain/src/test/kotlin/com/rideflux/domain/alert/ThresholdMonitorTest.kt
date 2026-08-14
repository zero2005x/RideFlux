/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.alert

import app.cash.turbine.test
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.telemetry.WheelTelemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ThresholdMonitorTest {
    @Test
    fun `requires three consecutive samples`() = runTest {
        val telemetry = MutableStateFlow(WheelTelemetry.EMPTY)
        val thresholds = MutableStateFlow(AlertThresholds(speedLimitKmh = 20f))
        val monitor = ThresholdMonitor(telemetry, thresholds, backgroundScope)
        runCurrent()

        monitor.alerts.test {
            telemetry.value = sample(21f, 1L); runCurrent()
            telemetry.value = sample(19f, 2L); runCurrent()
            telemetry.value = sample(21f, 3L); runCurrent()
            telemetry.value = sample(22f, 4L); runCurrent()
            expectNoEvents()
            telemetry.value = sample(23f, 5L); runCurrent()
            assertEquals(ThresholdAlert.Overspeed(23f, 20f), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `settings changes do not count the same telemetry frame twice`() = runTest {
        val telemetry = MutableStateFlow(WheelTelemetry.EMPTY)
        val thresholds = MutableStateFlow(AlertThresholds(speedLimitKmh = 20f))
        val monitor = ThresholdMonitor(telemetry, thresholds, backgroundScope)
        runCurrent()

        monitor.alerts.test {
            telemetry.value = sample(30f, 1L); runCurrent()
            thresholds.value = thresholds.value.copy(speedLimitKmh = 25f); runCurrent()
            thresholds.value = thresholds.value.copy(speedLimitKmh = 26f); runCurrent()
            expectNoEvents()

            telemetry.value = sample(30f, 2L); runCurrent()
            expectNoEvents()
            telemetry.value = sample(30f, 3L); runCurrent()
            assertEquals(ThresholdAlert.Overspeed(30f, 26f), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retrigger requires hysteresis recovery and cooldown`() = runTest {
        var now = 0L
        val telemetry = MutableStateFlow(WheelTelemetry.EMPTY)
        val thresholds = MutableStateFlow(AlertThresholds(speedLimitKmh = 20f))
        val monitor = ThresholdMonitor(telemetry, thresholds, backgroundScope, { now })
        runCurrent()

        monitor.alerts.test {
            emitSpeeds(telemetry, 21f, 22f, 23f)
            awaitItem()
            now = 11_000L
            emitSpeeds(telemetry, 21f, 22f, 23f)
            expectNoEvents()
            telemetry.value = sample(18f, 20L); runCurrent()
            emitSpeeds(telemetry, 21f, 22f, 24f)
            assertEquals(ThresholdAlert.Overspeed(24f, 20f), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.emitSpeeds(
        telemetry: MutableStateFlow<WheelTelemetry>,
        vararg speeds: Float,
    ) {
        speeds.forEachIndexed { index, speed ->
            telemetry.value = sample(speed, 10L + index)
            runCurrent()
        }
    }

    private fun sample(speed: Float, timestamp: Long) =
        WheelTelemetry(timestampMillis = timestamp, speedKmh = speed)
}
