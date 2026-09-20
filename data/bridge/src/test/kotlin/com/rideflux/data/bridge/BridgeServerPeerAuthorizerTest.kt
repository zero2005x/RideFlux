/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeServerPeerAuthorizerTest {

    @Test
    fun `AcceptAny authorizer accepts all tokens and addresses`() {
        val authorizer = BridgeServerPeerAuthorizer.FromApproved(
            approvedTokens = { setOf(VALID_TOKEN_HEX) },
            approvedMacs = { setOf(VALID_MAC) },
        )
        assertTrue(authorizer.isAuthorizedPeer(VALID_MAC, VALID_TOKEN))
        assertTrue(authorizer.isAuthorizedPeer(OTHER_MAC, VALID_TOKEN))
    }

    @Test
    fun `FromApproved authorizer matches approved token ignoring case`() {
        val authorizer = BridgeServerPeerAuthorizer.FromApproved(
            approvedTokens = { setOf(VALID_TOKEN_HEX.lowercase()) },
            approvedMacs = { emptySet() },
        )
        assertTrue(authorizer.isAuthorizedPeer(OTHER_MAC, VALID_TOKEN))
        assertFalse(authorizer.isAuthorizedPeer(OTHER_MAC, OTHER_TOKEN))
    }

    @Test
    fun `FromApproved authorizer matches approved legacy MAC ignoring case`() {
        val authorizer = BridgeServerPeerAuthorizer.FromApproved(
            approvedTokens = { emptySet() },
            approvedMacs = { setOf("AC:86:D1:55:D6:F9") },
        )
        assertTrue(authorizer.isAuthorizedPeer("ac:86:d1:55:d6:f9", null))
        assertFalse(authorizer.isAuthorizedPeer("11:22:33:44:55:66", null))
    }

    @Test
    fun `FromApproved rejects unknown peer with unknown token and address`() {
        val authorizer = BridgeServerPeerAuthorizer.FromApproved(
            approvedTokens = { setOf(VALID_TOKEN_HEX) },
            approvedMacs = { setOf(VALID_MAC) },
        )
        assertFalse(authorizer.isAuthorizedPeer(OTHER_MAC, OTHER_TOKEN))
        assertFalse(authorizer.isAuthorizedPeer(OTHER_MAC, null))
        assertFalse(authorizer.isAuthorizedPeer(null, OTHER_TOKEN))
        assertFalse(authorizer.isAuthorizedPeer(null, null))
    }

    private companion object {
        val VALID_TOKEN = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val VALID_TOKEN_HEX = "0123456789abcdef"
        val OTHER_TOKEN = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
        const val VALID_MAC = "AC:86:D1:55:D6:F9"
        const val OTHER_MAC = "11:22:33:44:55:66"
    }
}
