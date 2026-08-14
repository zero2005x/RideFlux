/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyv

import com.rideflux.domain.codec.DecodeEvent
import org.junit.Assert.assertTrue
import org.junit.Test

class VeteranWheelCodecTest {

    @Test
    fun `incomplete input cannot grow the frame buffer without bound`() {
        val codec = VeteranWheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState() as VeteranWheelCodec.VeteranState
        val incomplete = ByteArray(VeteranWheelCodec.MAX_FRAME_BUFFER_SIZE * 3) { 0xDC.toByte() }

        val events = codec.decode(state, incomplete)

        assertTrue(state.buffer.size <= VeteranWheelCodec.MAX_FRAME_BUFFER_SIZE)
        assertTrue(events.any { it is DecodeEvent.Malformed && "overflow" in it.reason })
    }
}
