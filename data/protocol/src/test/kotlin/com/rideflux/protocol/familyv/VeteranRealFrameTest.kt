/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyv

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Family V frames as real wheels send them.
 *
 * The three vectors are complete frames taken from WheelLog's unit tests (GPL-3.0,
 * `VeteranAdapterTest.kt`; see NOTICE): three different boards / firmware generations, provenance
 * beyond that not stated. Each is exactly 36 bytes: `DC 5A 5C`, then the value `0x20` at offset
 * 3, and every field at the offsets [VeteranDecoder] already reads. Offset 3 is the frame length
 * (`0x20` = 32 bytes after the four header bytes); offset 4 is the voltage's high byte, not a
 * length.
 */
class VeteranRealFrameTest {

    private val oldBoard = hex(
        "DC5A5C2025D600003BF500003BF50000FFDE1399" + "0DEF0000024602460000000000000000",
    )
    private val newBoard = hex(
        "DC5A5C20238A0112121A00004D450005064611F2" + "0E1000000AF00AF0041B000300000000",
    )
    private val firmware58 = hex(
        "DC5A5C2025CD0000071F0000C77800280000110B" + "0E1000010AF00AF00422000300140000",
    )

    private fun VeteranDecoder.DecodeResult.frame(): Pair<VeteranFrame, Int> {
        assertTrue("expected a decoded frame but got $this", this is VeteranDecoder.DecodeResult.Ok)
        this as VeteranDecoder.DecodeResult.Ok
        return frame to consumedBytes
    }

    @Test
    fun `each real frame decodes on its own and consumes exactly its 36 bytes`() {
        val (old, oldConsumed) = VeteranDecoder.decode(oldBoard).frame()
        assertEquals(36, oldConsumed)
        assertEquals(96.86, old.voltageVolts, 1e-9)
        assertEquals(15_349L, old.tripMeters)
        assertEquals(15_349L, old.totalMeters)
        assertEquals(50.17, old.temperatureCelsius, 1e-9)
        assertEquals(0, old.speedTenthsKmh)

        val (new, newConsumed) = VeteranDecoder.decode(newBoard).frame()
        assertEquals(36, newConsumed)
        assertEquals(90.98, new.voltageVolts, 1e-9)
        assertEquals(274, new.speedTenthsKmh)
        assertEquals(4_634L, new.tripMeters)
        assertEquals(347_461L, new.totalMeters)
        assertEquals(45.94, new.temperatureCelsius, 1e-9)

        val (fw, fwConsumed) = VeteranDecoder.decode(firmware58).frame()
        assertEquals(36, fwConsumed)
        assertEquals(96.77, fw.voltageVolts, 1e-9)
        assertEquals(1_823L, fw.tripMeters)
        assertEquals(2_672_504L, fw.totalMeters)
        assertEquals("001.0.58", fw.firmwareVersionString)
        assertEquals(0.20, fw.pitchAngleDegrees, 1e-9)
    }

    @Test
    fun `back to back frames are each decoded, whatever the notification boundaries are`() {
        val stream = oldBoard + newBoard + firmware58 + oldBoard
        for (chunkSize in listOf(20, 16, 7, 1)) {
            val codec = VeteranWheelCodec("AA:BB:CC:DD:EE:FF")
            val state = codec.newState()
            val voltages = stream.toList().chunked(chunkSize)
                .flatMap { codec.decode(state, it.toByteArray()) }
                .filterIsInstance<DecodeEvent.TelemetryUpdate>()
                .map { it.snapshot.voltageV }

            assertEquals("chunk size $chunkSize", listOf(96.86f, 90.98f, 96.77f, 96.86f), voltages)
        }
    }

    @Test
    fun `the voltage does not decide whether a frame is accepted`() {
        // The same real frame with the voltage field replaced: a full 24 S pack, a 16 S pack and
        // a nearly flat one. The frame length lives at offset 3, so none of these change how many
        // bytes the frame has or whether a CRC follows.
        for (hundredthsV in listOf(10_080, 6_720, 5_000)) {
            val frame = firmware58.copyOf()
            frame[4] = (hundredthsV ushr 8).toByte()
            frame[5] = hundredthsV.toByte()

            val (decoded, consumed) = VeteranDecoder.decode(frame).frame()

            assertEquals("voltage $hundredthsV", 36, consumed)
            assertEquals(hundredthsV, decoded.voltageHundredthsV)
        }
    }

    @Test
    fun `a frame cut short waits for the rest instead of being dropped`() {
        val result = VeteranDecoder.decode(oldBoard.copyOf(35))

        assertTrue(result is VeteranDecoder.DecodeResult.Fail)
        assertEquals(VeteranDecoder.DecodeError.TooShort, (result as VeteranDecoder.DecodeResult.Fail).error)
    }
}
