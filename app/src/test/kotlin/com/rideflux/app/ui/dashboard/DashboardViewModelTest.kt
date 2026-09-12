/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardViewModelTest {
    @Test fun displaySpeed_metricKeepsValue() {
        val s = DashboardUiState(useMetric = true)
        assertEquals(20f, s.displaySpeed(20f))
    }
    @Test fun displaySpeed_imperialConverts() {
        val s = DashboardUiState(useMetric = false)
        assertEquals(12.427f, s.displaySpeed(20f)!!, 0.01f)
    }
    @Test fun displaySpeed_nullIsNull() {
        val s = DashboardUiState(useMetric = true)
        assertNull(s.displaySpeed(null))
    }
    @Test fun displayDistance_metricKm() {
        val s = DashboardUiState(useMetric = true)
        assertEquals(1.5, s.displayDistance(1500)!!, 0.001)
    }
    @Test fun displayDistance_imperialMi() {
        val s = DashboardUiState(useMetric = false)
        assertEquals(0.932, s.displayDistance(1500)!!, 0.001)
    }
    @Test fun powerW_product() {
        val s = DashboardUiState(voltageV = 80f, currentA = 5f)
        assertEquals(400f, s.powerW!!, 0.001f)
    }
    @Test fun powerW_nullWhenMissing() {
        assertNull(DashboardUiState(voltageV = null, currentA = 5f).powerW)
        assertNull(DashboardUiState(voltageV = 80f, currentA = null).powerW)
    }
    @Test fun historyLimit_is600() { assertEquals(600, DashboardViewModel.HISTORY_LIMIT) }
    @Test fun alertTtl_is6000() { assertEquals(6000L, DashboardViewModel.ALERT_TTL_MILLIS) }
}
