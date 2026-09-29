/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CHECK byte is escaped like any body byte: a CHECK of `55`, `AA` or `A5` arrives as
 * `A5 xx`. In the 498 frames of three real captures this happens 8 times (about 1.6 %).
 */
class InmotionI1EscapedCheckTest {

    @Test
    fun `a frame whose CHECK is escaped decodes and is consumed whole`() {
        val wire = InmotionI1RealFrames.escapedCheck

        val result = InmotionI1Decoder.decode(wire)

        assertTrue("expected a frame but got $result", result is InmotionI1DecodeResult.Ok)
        result as InmotionI1DecodeResult.Ok
        assertEquals(wire.size, result.consumedBytes)
        assertEquals(0x0F550113L, result.frame.canId)
    }

    @Test
    fun `a frame with a plain CHECK still decodes`() {
        val wire = InmotionI1RealFrames.plainCheck

        val result = InmotionI1Decoder.decode(wire)

        assertTrue("expected a frame but got $result", result is InmotionI1DecodeResult.Ok)
        assertEquals(wire.size, (result as InmotionI1DecodeResult.Ok).consumedBytes)
    }

    @Test
    fun `no frame of the stream is lost to an escaped CHECK`() {
        val stream = InmotionI1RealFrames.all.reduce { a, b -> a + b }
        var offset = 0
        var frames = 0
        while (offset < stream.size) {
            val result = InmotionI1Decoder.decode(stream, offset)
            if (result !is InmotionI1DecodeResult.Ok) break
            frames++
            offset += result.consumedBytes
        }

        assertEquals(InmotionI1RealFrames.all.size, frames)
        assertEquals(stream.size, offset)
    }

    @Test
    fun `a wrong CHECK is still refused`() {
        val wire = InmotionI1RealFrames.plainCheck.copyOf()
        wire[wire.size - 3] = 0x78 // the CHECK byte, one off

        val result = InmotionI1Decoder.decode(wire)

        assertTrue(result is InmotionI1DecodeResult.Fail)
        assertTrue((result as InmotionI1DecodeResult.Fail).error is InmotionI1DecodeError.BadChecksum)
    }
}
