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

    @Test fun selectBondedGlasses_prefersUserExplicitSelection() {
        val devices = listOf(
            "AA:BB:CC:01" to "Rokid Vision",
            "AA:BB:CC:02" to "My AR Glasses",
            "AA:BB:CC:03" to "Generic Bluetooth",
        )
        // User explicitly picked "AA:BB:CC:02"
        val selected = selectBondedGlasses(devices, "AA:BB:CC:02")
        assertEquals("AA:BB:CC:02", selected)
    }

    @Test fun selectBondedGlasses_fallsBackToNameHeuristicWhenUnset() {
        val devices = listOf(
            "AA:BB:CC:01" to "Some Headphones",
            "AA:BB:CC:02" to "Rokid Vision Pro",
            "AA:BB:CC:03" to "Smart Glass 2",
        )
        // No preferred MAC; prefers "rokid" over "glass"
        val selected = selectBondedGlasses(devices, null)
        assertEquals("AA:BB:CC:02", selected)
    }

    @Test fun selectBondedGlasses_returnsNullWhenNoMatch() {
        val devices = listOf(
            "AA:BB:CC:01" to "Car Audio",
            "AA:BB:CC:02" to "Watch",
        )
        assertNull(selectBondedGlasses(devices, null))
    }
}
