/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import com.rideflux.data.bridge.BridgeFrame
import com.rideflux.data.bridge.BridgeCodec
import com.rideflux.data.bridge.SignalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RokidCxrPublisherTest {
    @Test fun cxrBackoff_cappedAt15s() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 15000L, 15000L), (0L..5L).map(::cxrReconnectBackoffMillis))
    }
    @Test fun bridgeCodec_rejectsNonFiniteSpeedBeforeEncode() {
        var threw = false
        try { BridgeFrame(timestampMillis = 0, speedKmh = Float.NaN, vehicleBatteryPercent = null, phoneBatteryPercent = null, voltageV = null, tripDistanceMetres = null, tripDurationSeconds = null, signal = SignalLevel.NONE, stale = false, ready = false) } catch (_: Throwable) { threw = true }
        assertTrue(threw)
    }
    @Test fun goodFrame_encodesAndDecodes() {
        val f = BridgeFrame(1000, 20f, 80f, 50, 84f, 100, 60, SignalLevel.GOOD, false, true)
        val c = BridgeCodec.decode(BridgeCodec.encode(f))
        assertEquals(f.speedKmh, c?.speedKmh)
    }
}
