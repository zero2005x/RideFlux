/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.source

import com.rideflux.data.bridge.BridgePeerFilter
import com.rideflux.hud.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Which identity the HUD trusts is the difference between "connects"
 * and "silently rejects every frame forever", so pin the precedence
 * down rather than leaving it to the reader of a `when` block.
 */
class PeerFilterSelectionTest {

    @Test
    fun `a stored token wins over a stored MAC`() {
        // The MAC is a rotating random address; the token is the only
        // identity that survives rotation, so it must take precedence
        // even when both are present.
        val filter = peerFilterFor(TOKEN, "AA:BB:CC:DD:EE:FF")

        assertEquals(BridgePeerFilter.PairingToken(TOKEN), filter)
    }

    @Test
    fun `a HUD paired before tokens existed falls back to its stored MAC`() {
        val filter = peerFilterFor(null, "AA:BB:CC:DD:EE:FF")

        assertEquals(BridgePeerFilter.Allowlist(setOf("AA:BB:CC:DD:EE:FF")), filter)
    }

    @Test
    fun `an unpaired HUD refuses unverified peers in release builds`() {
        val filter = peerFilterFor(null, null)

        val expected = if (BuildConfig.DEBUG) {
            BridgePeerFilter.AcceptAny
        } else {
            BridgePeerFilter.RejectAll
        }
        assertEquals(expected, filter)
    }

    @Test
    fun `filters for different phones are not interchangeable`() {
        // Re-pairing with another phone must produce a filter that does
        // not compare equal to the old one, otherwise HudViewModel's
        // in-place source rebuild would look like a no-op.
        val other = TOKEN.copyOf().also { it[0] = 0x99.toByte() }

        assertNotEquals(peerFilterFor(TOKEN, null), peerFilterFor(other, null))
    }

    private companion object {
        val TOKEN = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
    }
}
