/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import com.rideflux.data.bridge.SignalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeStandbyTest {
    @Test
    fun standbyFrame_isAnExplicitIdleHeartbeat() {
        val frame = standbyFrame(nowMillis = 42L, phoneBatteryPercent = 63)

        assertEquals(42L, frame.timestampMillis)
        assertFalse(frame.ready)
        assertTrue(frame.stale)
        assertEquals(SignalLevel.NONE, frame.signal)
        assertNull(frame.speedKmh)
        assertNull(frame.vehicleBatteryPercent)
        assertEquals(63, frame.phoneBatteryPercent)
    }

    @Test
    fun reconnectBackoff_isExponentialAndCappedAt15Seconds() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L),
            (0L..5L).map(::reconnectBackoffMillis),
        )
    }

    @Test
    fun cxrReconnectBackoff_isExponentialAndCappedAt15Seconds() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L),
            (0L..5L).map(::cxrReconnectBackoffMillis),
        )
    }
}
