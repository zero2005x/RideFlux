/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.source

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgePeerScannerTest {
    @Test fun peerCandidate_sortedByRssi() {
        val a = BridgePeerCandidate("AA", "A", -60)
        val b = BridgePeerCandidate("BB", "B", -40)
        val sorted = listOf(a, b).sortedByDescending(BridgePeerCandidate::rssi)
        assertEquals("BB", sorted.first().address)
    }
    @Test fun peerCandidate_fields() {
        val c = BridgePeerCandidate("AA:BB:CC:DD:EE:FF", "RideFlux", -55)
        assertEquals("AA:BB:CC:DD:EE:FF", c.address)
        assertEquals("RideFlux", c.name)
        assertEquals(-55, c.rssi)
    }
}
