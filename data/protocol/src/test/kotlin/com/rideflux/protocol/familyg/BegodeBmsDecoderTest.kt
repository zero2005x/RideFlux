/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyg

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.telemetry.BmsFrame
import com.rideflux.protocol.testutil.hex
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class BegodeBmsDecoderTest {
    private val cycle = javaClass.getResourceAsStream("/begode/cap-a-first-cycle.hex")!!
        .bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.map(::hex).toList()
        }

    @Test fun `owner capture type 1 is retained exactly with no physical interpretation`() {
        val bytes = cycle[1]
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals("97b8de431ada275c78ffe4bb64f71110721473a4bdb578b20e6f357a4864ef00", digest)
        val raw = (BegodeBmsDecoder.decode(bytes) as BegodeBmsResult.Raw).frame
        assertEquals(1, raw.typeCode)
        assertEquals(0, raw.subIndex)
        assertEquals(bytes.toList(), raw.bytes)
        assertUnknown(raw)
        bytes.fill(0)
        assertEquals(0x55.toByte(), raw.bytes.first())
    }

    @Test fun `all BMS types and all discriminator bytes remain raw`() {
        // Synthetic envelopes only: no capture of types 2,3,5,6 is available.
        for (type in listOf(1, 2, 3, 5, 6)) {
            for (subIndex in 0..255) {
                val bytes = cycle[1].copyOf().apply {
                    this[18] = type.toByte()
                    this[19] = subIndex.toByte()
                }
                val raw = (BegodeBmsDecoder.decode(bytes) as BegodeBmsResult.Raw).frame
                assertEquals(type, raw.typeCode)
                assertEquals(subIndex, raw.subIndex)
                assertEquals(bytes.toList(), raw.bytes)
                assertUnknown(raw)
            }
        }
    }

    @Test fun `short oversized zero and corrupt envelopes are malformed without exceptions`() {
        for (size in 0..23) {
            assertSame(BegodeBmsResult.Malformed, BegodeBmsDecoder.decode(cycle[1].copyOf(size)))
        }
        assertSame(BegodeBmsResult.Malformed, BegodeBmsDecoder.decode(cycle[1].copyOf(25)))
        assertSame(BegodeBmsResult.Malformed, BegodeBmsDecoder.decode(ByteArray(24)))
        for (offset in listOf(0, 1, 20, 21, 22, 23)) {
            val damaged = cycle[1].copyOf().apply { this[offset] = 0 }
            assertSame(BegodeBmsResult.Malformed, BegodeBmsDecoder.decode(damaged))
        }
    }

    @Test fun `valid zero and arbitrary payloads never become plausible readings`() {
        for (type in listOf(1, 2, 3, 5, 6)) {
            for (value in listOf(0.toByte(), 0xff.toByte())) {
                val bytes = cycle[1].copyOf().apply {
                    fill(value, 2, 18)
                    this[18] = type.toByte()
                }
                assertUnknown((BegodeBmsDecoder.decode(bytes) as BegodeBmsResult.Raw).frame)
            }
        }
    }

    @Test fun `other valid packet types are unsupported by BMS parser`() {
        for (type in (0..255).filter { it !in listOf(1, 2, 3, 5, 6) }) {
            val bytes = cycle[1].copyOf().apply { this[18] = type.toByte() }
            assertSame(BegodeBmsResult.Unsupported, BegodeBmsDecoder.decode(bytes))
        }
    }

    @Test fun `fragmented owner cycle emits one raw event and preserves existing telemetry`() {
        val codec = BegodeWheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState()
        val stream = cycle.reduce { a, b -> a + b }
        val events = stream.toList().chunked(20).flatMap { codec.decode(state, it.toByteArray()) }
        val raw = events.filterIsInstance<DecodeEvent.RawBmsFrame>().single().frame
        assertEquals(cycle[1].toList(), raw.bytes)
        assertEquals(1, events.filterIsInstance<DecodeEvent.Identified>().size)
        val expectedCodec = BegodeWheelCodec("AA:BB:CC:DD:EE:FF")
        val expectedState = expectedCodec.newState()
        val expected = (cycle[0] + cycle[2] + cycle[3]).toList().chunked(20)
            .flatMap { expectedCodec.decode(expectedState, it.toByteArray()) }
            .filterIsInstance<DecodeEvent.TelemetryUpdate>().map { it.snapshot.copy(timestampMillis = 0) }
        val actual = events.filterIsInstance<DecodeEvent.TelemetryUpdate>()
            .map { it.snapshot.copy(timestampMillis = 0) }
        assertEquals(expected, actual)
        assertTrue(codec.keepAliveFrames(state).isEmpty())
        assertEquals(listOf(listOf(0x4e.toByte()), listOf(0x56.toByte())),
            codec.handshakeFrames(state).map { it.toList() })
    }

    @Test fun `raw only stream does not identify or publish retained telemetry`() {
        val codec = BegodeWheelCodec()
        val state = codec.newState()
        val events = codec.decode(state, cycle[1])
        assertTrue(events.single() is DecodeEvent.RawBmsFrame)
        assertTrue(codec.decode(state, cycle[1].copyOf().apply { this[18] = 0x77 }).isEmpty())
    }

    private fun assertUnknown(frame: BmsFrame) {
        assertNull(frame.packVoltageV)
        assertNull(frame.packCurrentA)
        assertNull(frame.cellCount)
        assertNull(frame.cellVoltagesV)
        assertNull(frame.temperaturesC)
        assertNull(frame.minimumCellVoltageV)
        assertNull(frame.maximumCellVoltageV)
        assertNull(frame.cellVoltageDifferenceV)
        assertNull(frame.stateOfChargePercent)
    }
}
