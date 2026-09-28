/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the HUD-visibility command riding in the frame's flags byte.
 *
 * The command shares the telemetry frame precisely so that mixed
 * installs keep working, so the compatibility cases matter as much as
 * the round trip.
 */
class BridgeHudVisibilityTest {

    private fun frame(hudHidden: Boolean) = BridgeFrame(
        timestampMillis = 1_700_000_000_000L,
        speedKmh = 24.5f,
        vehicleBatteryPercent = 71f,
        phoneBatteryPercent = 63,
        voltageV = 82.4f,
        tripDistanceMetres = 4_200,
        tripDurationSeconds = 872L,
        signal = SignalLevel.GOOD,
        stale = false,
        ready = true,
        hudHidden = hudHidden,
    )

    @Test
    fun hiddenFlag_survivesTheRoundTrip() {
        for (hidden in listOf(false, true)) {
            val decoded = BridgeCodec.decode(BridgeCodec.encode(frame(hidden)))

            assertNotNull(decoded)
            assertEquals(hidden, decoded!!.hudHidden)
        }
    }

    @Test
    fun hiddenFlag_doesNotDisturbTheOtherFields() {
        val visible = BridgeCodec.decode(BridgeCodec.encode(frame(hudHidden = false)))!!
        val hidden = BridgeCodec.decode(BridgeCodec.encode(frame(hudHidden = true)))!!

        assertEquals(visible.speedKmh, hidden.speedKmh)
        assertEquals(visible.vehicleBatteryPercent, hidden.vehicleBatteryPercent)
        assertEquals(visible.tripDistanceMetres, hidden.tripDistanceMetres)
        assertEquals(visible.signal, hidden.signal)
        assertEquals(visible.stale, hidden.stale)
        assertEquals(visible.ready, hidden.ready)
    }

    @Test
    fun frameFromAnOlderPhone_decodesAsVisible() {
        // A phone on the previous build never sets bit 4, which must
        // read as "no opinion, keep showing the HUD" rather than
        // blanking the display of anyone mid-upgrade.
        val bytes = BridgeCodec.encode(frame(hudHidden = true))
        bytes[2] = (bytes[2].toInt() and 0x10.inv()).toByte()

        val decoded = BridgeCodec.decode(bytes)

        assertNotNull(decoded)
        assertFalse(decoded!!.hudHidden)
    }

    @Test
    fun unknownReservedBits_doNotRejectTheFrame() {
        // Bits 5..7 are reserved for later flags; a HUD on today's build
        // must ignore them rather than drop the frame, or the next
        // protocol addition would blank every older pair of glasses.
        val bytes = BridgeCodec.encode(frame(hudHidden = true))
        bytes[2] = (bytes[2].toInt() or 0xE0).toByte()

        val decoded = BridgeCodec.decode(bytes)

        assertNotNull("reserved bits must decode as don't-care", decoded)
        assertTrue(decoded!!.hudHidden)
        assertTrue(decoded.ready)
    }

    @Test
    fun defaultConstruction_isVisible() {
        assertFalse(BridgeFrame.EMPTY.hudHidden)
    }
}
