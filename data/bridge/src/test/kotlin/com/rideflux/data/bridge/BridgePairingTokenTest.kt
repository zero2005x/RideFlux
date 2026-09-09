/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgePairingTokenTest {

    @Test
    fun `generated tokens are the advertised length and not constant`() {
        val first = BridgePairingToken.generate()
        val second = BridgePairingToken.generate()

        assertEquals(BridgeProtocol.PAIRING_TOKEN_SIZE, first.size)
        assertEquals(BridgeProtocol.PAIRING_TOKEN_SIZE, second.size)
        // A fixed token would silently pair every phone with every pair
        // of glasses, so assert the source of randomness is live.
        assertNotEquals(BridgePairingToken.toHex(first), BridgePairingToken.toHex(second))
    }

    @Test
    fun `hex round-trips`() {
        val token = BridgePairingToken.generate()

        assertArrayEquals(token, BridgePairingToken.fromHex(BridgePairingToken.toHex(token)))
    }

    @Test
    fun `hex encoding is lowercase and zero-padded`() {
        val token = byteArrayOf(0x00, 0x0F, 0x10, 0x7F, 0x80.toByte(), 0xAB.toByte(), 0xFF.toByte(), 0x01)

        assertEquals("000f107f80abff01", BridgePairingToken.toHex(token))
    }

    @Test
    fun `fromHex tolerates surrounding whitespace and uppercase`() {
        assertArrayEquals(
            byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D, 0x4E, 0x5F, 0x60, 0x71),
            BridgePairingToken.fromHex("  0A1B2C3D4E5F6071 "),
        )
    }

    @Test
    fun `fromHex rejects anything that is not a whole token`() {
        // A malformed preference must read as "not paired" rather than
        // as a token that can never match.
        assertNull(BridgePairingToken.fromHex(null))
        assertNull(BridgePairingToken.fromHex(""))
        assertNull(BridgePairingToken.fromHex("0011223344556677889900"))
        assertNull(BridgePairingToken.fromHex("00112233445566"))
        assertNull(BridgePairingToken.fromHex("001122334455667z"))
    }

    @Test
    fun `display code groups the whole token for the phone settings screen`() {
        val token = byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(), 0xE5.toByte(), 0xF6.toByte(), 0x07, 0x18)

        assertEquals("A1B2-C3D4-E5F6-0718", BridgePairingToken.displayCode(token))
    }

    @Test
    fun `short code is the leading four characters the HUD picker shows`() {
        val token = byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(), 0xE5.toByte(), 0xF6.toByte(), 0x07, 0x18)

        assertEquals("A1B2", BridgePairingToken.shortCode(token))
        assertEquals("A1B2", BridgePairingToken.shortCodeOfHex(BridgePairingToken.toHex(token)))
        assertNull(BridgePairingToken.shortCodeOfHex("nonsense"))
    }
}
