/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi1

import com.rideflux.domain.codec.DecodeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A frame arrives in several BLE notifications, and a notification can end right after the
 * escape marker `A5`, before the byte it escapes. That is an incomplete frame, not a broken
 * one: the rest is on its way. Real V5F frames escape about one byte in a hundred, so with
 * 20-byte notifications it happens regularly.
 */
class InmotionI1SplitEscapeTest {

    /** Wire bytes of a frame with the given unstuffed body (escaped body, escaped CHECK). */
    private fun wireOf(body: ByteArray): ByteArray {
        val check = InmotionI1Codec.checksum8(body)
        val checkBytes =
            if (check == 0xAA || check == 0x55 || check == 0xA5) {
                byteArrayOf(InmotionI1Codec.ESCAPE_BYTE, check.toByte())
            } else {
                byteArrayOf(check.toByte())
            }
        return byteArrayOf(InmotionI1Codec.PREAMBLE_BYTE, InmotionI1Codec.PREAMBLE_BYTE) +
            InmotionI1Codec.escape(body) + checkBytes +
            byteArrayOf(InmotionI1Codec.TRAILER_BYTE, InmotionI1Codec.TRAILER_BYTE)
    }

    /**
     * A real frame with three bytes of its extended data (all in a region that carries no decoded
     * field) replaced by the three values that must be escaped, and the CHECK recomputed. Its
     * escape markers sit deep inside the frame instead of only in the CAN id.
     */
    private val heavilyEscaped: ByteArray = run {
        val frame = (InmotionI1Decoder.decode(InmotionI1RealFrames.plainCheck) as InmotionI1DecodeResult.Ok).frame
        val exData = frame.exData.copyOf()
        exData[4] = 0xAA.toByte()
        exData[5] = 0xA5.toByte()
        exData[6] = 0x55.toByte()
        val canId = ByteArray(4) { (frame.canId ushr (8 * it)).toByte() }
        wireOf(
            canId + frame.data8 +
                byteArrayOf(frame.lenByte.toByte(), frame.chan.toByte(), frame.fmt.toByte(), frame.type.toByte()) +
                exData,
        )
    }

    private fun feed(stream: ByteArray, chunkSize: Int): List<DecodeEvent> {
        val codec = InmotionI1WheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState()
        return stream.toList().chunked(chunkSize).flatMap { codec.decode(state, it.toByteArray()) }
    }

    @Test
    fun `the derived frame itself decodes`() {
        val result = InmotionI1Decoder.decode(heavilyEscaped)

        assertTrue("expected a frame but got $result", result is InmotionI1DecodeResult.Ok)
        assertEquals(heavilyEscaped.size, (result as InmotionI1DecodeResult.Ok).consumedBytes)
        assertEquals(0xAA.toByte(), result.frame.exData[4])
        assertEquals(0xA5.toByte(), result.frame.exData[5])
        assertEquals(0x55.toByte(), result.frame.exData[6])
    }

    @Test
    fun `every frame is decoded at every notification size`() {
        val stream = heavilyEscaped + InmotionI1RealFrames.escapedCheck + InmotionI1RealFrames.plainCheck +
            heavilyEscaped + InmotionI1RealFrames.positivePitch
        val frames = 5

        for (chunkSize in 1..stream.size) {
            val events = feed(stream, chunkSize)

            assertEquals(
                "telemetry updates with chunk size $chunkSize",
                frames,
                events.count { it is DecodeEvent.TelemetryUpdate },
            )
            assertTrue(
                "malformed events with chunk size $chunkSize: ${events.filterIsInstance<DecodeEvent.Malformed>()}",
                events.none { it is DecodeEvent.Malformed },
            )
        }
    }

    @Test
    fun `a frame cut right after any escape marker waits for the escaped byte`() {
        val markers = ArrayList<Int>()
        var i = 2
        while (i < heavilyEscaped.size - 3) {
            if (heavilyEscaped[i] == InmotionI1Codec.ESCAPE_BYTE) {
                markers += i
                i += 2
            } else {
                i += 1
            }
        }
        // The cut must not be rejected for its length alone, so only markers past the minimum size count.
        val deep = markers.filter { it + 1 >= 21 }
        assertTrue("expected deep escape markers, found $markers", deep.size >= 3)

        for (marker in deep) {
            val result = InmotionI1Decoder.decode(heavilyEscaped.copyOf(marker + 1))

            assertTrue("cut at $marker: $result", result is InmotionI1DecodeResult.Fail)
            assertEquals("cut at $marker", InmotionI1DecodeError.TooShort, (result as InmotionI1DecodeResult.Fail).error)
        }
    }

    @Test
    fun `an escaped CHECK cut after its marker waits for the escaped byte`() {
        val frame = InmotionI1RealFrames.escapedCheck
        val marker = frame.size - 4 // A5 55 | 55 55
        assertEquals(InmotionI1Codec.ESCAPE_BYTE, frame[marker])

        val result = InmotionI1Decoder.decode(frame.copyOf(marker + 1))

        assertTrue(result is InmotionI1DecodeResult.Fail)
        assertEquals(InmotionI1DecodeError.TooShort, (result as InmotionI1DecodeResult.Fail).error)
    }
}
