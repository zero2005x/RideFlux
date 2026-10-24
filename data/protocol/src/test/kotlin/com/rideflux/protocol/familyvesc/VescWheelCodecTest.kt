/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyvesc

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.command.WheelCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * VESC is read-only: the codec never writes, and every malformed frame must
 * be reported rather than silently dropped. These cover the framing branches
 * the end-to-end tests do not reach (three-byte length header, foreign state,
 * and the write surface).
 */
class VescWheelCodecTest {

    private class ForeignState : WheelCodec.State

    private val codec = VescWheelCodec(deviceAddress = "AA:BB:CC:DD:EE:01")

    @Test
    fun `the read-only codec encodes no writable frames`() {
        val state = codec.newState()

        assertTrue(codec.encode(state, WheelCommand.SetHeadlight(true)).isEmpty())
        assertTrue(codec.encode(state, WheelCommand.Beep).isEmpty())
    }

    @Test
    fun `a state from another codec is rejected loudly`() {
        try {
            codec.decode(ForeignState(), byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x03))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(
                "message should name the expected state, was: ${e.message}",
                e.message.orEmpty().contains("VescState"),
            )
        }
    }

    @Test
    fun `a three byte length header is framed and a bad CRC is reported`() {
        // 0x04 selects the three-byte length form. Length 3, then the payload,
        // a two-byte CRC that does not match, and the stop byte.
        val frame = byteArrayOf(0x04, 0x00, 0x00, 0x03, 0x01, 0x02, 0x03, 0x00, 0x00, 0x03)

        val events = codec.decode(codec.newState(), frame)

        assertEquals(1, events.size)
        val malformed = events.single() as DecodeEvent.Malformed
        assertTrue(
            "expected a CRC report, was: ${malformed.reason}",
            malformed.reason.contains("CRC mismatch"),
        )
    }

    @Test
    fun `a buffer with no start byte is dropped without an event`() {
        assertTrue(codec.decode(codec.newState(), byteArrayOf(0x05, 0x06, 0x07)).isEmpty())
    }

    @Test
    fun `an incomplete frame is held instead of decoded`() {
        // Header promises four payload bytes but the buffer stops early.
        assertTrue(codec.decode(codec.newState(), byteArrayOf(0x02, 0x04, 0x01)).isEmpty())
    }

    @Test
    fun `handshake and keep-alive both poll the setup values`() {
        val state = codec.newState()

        assertArrayEquals(codec.handshakeFrames(state).single(), codec.keepAliveFrames(state).single())
        assertTrue(codec.handshakeFrames(state).single().isNotEmpty())
        assertEquals(VescWheelCodec.POLL_PERIOD_MILLIS, codec.keepAlivePeriodMillis)
    }
}
