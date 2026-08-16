/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePeerFilterTest {

    @Test
    fun `allowlist matches only configured MAC ignoring case`() {
        val filter = BridgePeerFilter.Allowlist(setOf("AA:BB:CC:DD:EE:FF"))
        assertTrue(filter.acceptsAddress("aa:bb:cc:dd:ee:ff"))
        assertFalse(filter.acceptsAddress("11:22:33:44:55:66"))
        assertFalse(filter.acceptsAddress(null))
    }

    @Test
    fun `pairing token matches only the exact advertised token`() {
        val filter = BridgePeerFilter.PairingToken(TOKEN)

        assertTrue(filter.acceptsToken(TOKEN.copyOf()))
        assertFalse(filter.acceptsToken(OTHER_TOKEN))
    }

    @Test
    fun `pairing token rejects a peer advertising no token at all`() {
        // A phone running a build from before tokens existed, or an
        // unrelated device that happens to advertise the service UUID.
        assertFalse(BridgePeerFilter.PairingToken(TOKEN).acceptsToken(null))
    }

    @Test
    fun `pairing token rejects a truncated or padded token`() {
        val filter = BridgePeerFilter.PairingToken(TOKEN)

        assertFalse(filter.acceptsToken(TOKEN.copyOf(TOKEN.size - 1)))
        assertFalse(filter.acceptsToken(TOKEN + 0x00))
    }

    @Test
    fun `pairing token rejects tokens of the wrong length at construction`() {
        val tooShort = ByteArray(BridgeProtocol.PAIRING_TOKEN_SIZE - 1)

        val error = runCatching { BridgePeerFilter.PairingToken(tooShort) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `pairing token defends against later mutation of the source array`() {
        val source = TOKEN.copyOf()
        val filter = BridgePeerFilter.PairingToken(source)

        source[0] = (source[0] + 1).toByte()

        assertTrue("filter must hold its own copy", filter.acceptsToken(TOKEN))
    }

    @Test
    fun `pairing token equality is by value so source rebuilds stay stable`() {
        assertEquals(
            BridgePeerFilter.PairingToken(TOKEN),
            BridgePeerFilter.PairingToken(TOKEN.copyOf()),
        )
        assertEquals(
            BridgePeerFilter.PairingToken(TOKEN).hashCode(),
            BridgePeerFilter.PairingToken(TOKEN.copyOf()).hashCode(),
        )
        assertNotEquals(
            BridgePeerFilter.PairingToken(TOKEN),
            BridgePeerFilter.PairingToken(OTHER_TOKEN),
        )
    }

    private companion object {
        val TOKEN = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val OTHER_TOKEN = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEE.toByte())
    }
}
