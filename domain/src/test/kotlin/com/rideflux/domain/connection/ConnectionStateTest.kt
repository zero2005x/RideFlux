/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.connection

import com.rideflux.domain.wheel.WheelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateTest {

    @Test
    fun `every failure reason has a non-blank default message`() {
        val blank = ConnectionState.Failed.Reason.entries.filter {
            it.defaultMessage.isBlank()
        }
        assertEquals(emptyList<ConnectionState.Failed.Reason>(), blank)
    }

    @Test
    fun `default messages are distinct so the UI can tell failures apart`() {
        val messages = ConnectionState.Failed.Reason.entries.map { it.defaultMessage }
        assertEquals(messages.size, messages.toSet().size)
    }

    @Test
    fun `a failure may omit its message and fall back to the reason`() {
        val failure = ConnectionState.Failed(ConnectionState.Failed.Reason.GATT_ERROR)
        assertNull(failure.message)
        assertEquals(
            "Bluetooth connection error",
            failure.reason.defaultMessage,
        )
    }

    @Test
    fun `an explicit message does not replace the reason`() {
        val failure = ConnectionState.Failed(
            reason = ConnectionState.Failed.Reason.HANDSHAKE_TIMEOUT,
            message = "no response after 5s",
        )
        assertEquals("no response after 5s", failure.message)
        assertEquals(ConnectionState.Failed.Reason.HANDSHAKE_TIMEOUT, failure.reason)
    }

    @Test
    fun `handshaking carries the family resolved at the link layer`() {
        val state = ConnectionState.Handshaking(WheelFamily.K)
        assertEquals(WheelFamily.K, state.family)
        assertNotEquals(ConnectionState.Handshaking(WheelFamily.V), state)
    }

    @Test
    fun `terminal and steady states compare by identity`() {
        assertTrue(ConnectionState.Ready === ConnectionState.Ready)
        assertTrue(ConnectionState.Disconnected === ConnectionState.Disconnected)
        assertTrue(ConnectionState.Connecting === ConnectionState.Connecting)
        assertFalse(ConnectionState.Ready == ConnectionState.Disconnected)
    }

    /**
     * The reason names are persisted in logs and matched by support
     * tooling, so renaming one is a breaking change. Pin the set.
     */
    @Test
    fun `failure reason names are a stability contract`() {
        assertEquals(
            listOf(
                "BLE_LINK_LOST",
                "GATT_ERROR",
                "HANDSHAKE_TIMEOUT",
                "UNKNOWN_FAMILY",
                "CHECKSUM_STORM",
                "AUTHENTICATION_FAILED",
                "INTERNAL",
            ),
            ConnectionState.Failed.Reason.entries.map { it.name },
        )
    }
}
