/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.codec

import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the default methods on [WheelCodec].
 *
 * These are the two guarantees the rest of the stack leans on and that
 * no concrete codec test exercises:
 *
 *  - `decodeSafely` turns *any* escaping exception into a
 *    [DecodeEvent.Malformed], so one bad frame on the BLE callback path
 *    cannot tear down a live GATT session mid-ride.
 *  - `keepAliveSpec` refuses a non-empty frame list paired with a
 *    non-positive period, which would otherwise make the connection
 *    layer busy-loop writing to the wheel.
 */
class WheelCodecContractTest {

    private object FakeState : WheelCodec.State

    /**
     * Minimal codec whose behaviour each test dials in. Only the
     * abstract members are overridden; everything under test is a
     * default method on the interface.
     */
    private open class FakeCodec(
        private val onDecode: (ByteArray) -> List<DecodeEvent> = { emptyList() },
        private val keepAlive: List<ByteArray> = emptyList(),
        override val keepAlivePeriodMillis: Long = 0L,
    ) : WheelCodec {
        override val family: WheelFamily = WheelFamily.K
        override fun newState(): WheelCodec.State = FakeState
        override fun handshakeFrames(state: WheelCodec.State): List<ByteArray> = emptyList()
        override fun decode(state: WheelCodec.State, bytes: ByteArray): List<DecodeEvent> =
            onDecode(bytes)
        override fun encode(state: WheelCodec.State, command: WheelCommand): List<ByteArray> =
            emptyList()
        override fun keepAliveFrames(state: WheelCodec.State): List<ByteArray> = keepAlive
    }

    // ---- decodeSafely ---------------------------------------------------

    @Test
    fun `decodeSafely passes through the events of a well-behaved codec`() {
        val event = DecodeEvent.TelemetryUpdate(WheelTelemetry(timestampMillis = 7L))
        val codec = FakeCodec(onDecode = { listOf(event) })

        val events = codec.decodeSafely(FakeState, byteArrayOf(1, 2, 3))

        assertEquals(listOf(event), events)
    }

    @Test
    fun `decodeSafely converts a bounds error into Malformed instead of throwing`() {
        val codec = FakeCodec(onDecode = { it -> listOf(DecodeEvent.Alert(throw IndexOutOfBoundsException("index 9"))) })
        val bytes = byteArrayOf(0x52, 0x00)

        val events = codec.decodeSafely(FakeState, bytes)

        assertEquals(1, events.size)
        val malformed = events.single() as DecodeEvent.Malformed
        assertTrue(
            "Reason should name the exception type, was: ${malformed.reason}",
            malformed.reason.contains("IndexOutOfBoundsException"),
        )
        assertTrue(malformed.reason.contains("index 9"))
        // The offending bytes are handed back for diagnostics.
        assertSame(bytes, malformed.offendingBytes)
    }

    @Test
    fun `decodeSafely also absorbs the domain invariant failures`() {
        // A codec that lets a bad batteryPercent through hits the
        // WheelTelemetry init check; that must degrade, not propagate.
        val codec = FakeCodec(onDecode = {
            listOf(
                DecodeEvent.TelemetryUpdate(
                    WheelTelemetry(timestampMillis = 1L, batteryPercent = 900f),
                ),
            )
        })

        val events = codec.decodeSafely(FakeState, byteArrayOf(0))

        val malformed = events.single() as DecodeEvent.Malformed
        assertTrue(malformed.reason.contains("IllegalArgumentException"))
    }

    @Test
    fun `decodeSafely reports an empty frame without special-casing it`() {
        val codec = FakeCodec(onDecode = { emptyList() })
        assertEquals(emptyList<DecodeEvent>(), codec.decodeSafely(FakeState, ByteArray(0)))
    }

    // ---- keepAliveSpec --------------------------------------------------

    @Test
    fun `keepAliveSpec is null when the family needs no keep-alive`() {
        assertNull(FakeCodec().keepAliveSpec(FakeState))
    }

    @Test
    fun `keepAliveSpec pairs frames with a positive period`() {
        val frame = byteArrayOf(0x0A)
        val codec = FakeCodec(keepAlive = listOf(frame), keepAlivePeriodMillis = 25L)

        val spec = codec.keepAliveSpec(FakeState)!!

        assertEquals(listOf(frame), spec.frames)
        assertEquals(25L, spec.periodMillis)
    }

    @Test
    fun `keepAliveSpec rejects frames with a zero period rather than busy-looping`() {
        val codec = FakeCodec(keepAlive = listOf(byteArrayOf(1)), keepAlivePeriodMillis = 0L)

        val error = assertThrows(IllegalArgumentException::class.java) {
            codec.keepAliveSpec(FakeState)
        }
        assertTrue(
            "Message should explain the pairing rule, was: ${error.message}",
            error.message.orEmpty().contains("requires a positive period"),
        )
    }

    @Test
    fun `keepAliveSpec rejects a negative period`() {
        val codec = FakeCodec(keepAlive = listOf(byteArrayOf(1)), keepAlivePeriodMillis = -1L)
        assertThrows(IllegalArgumentException::class.java) { codec.keepAliveSpec(FakeState) }
    }

    @Test
    fun `KeepAliveSpec cannot be constructed empty or non-positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            WheelCodec.KeepAliveSpec(frames = emptyList(), periodMillis = 10L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WheelCodec.KeepAliveSpec(frames = listOf(byteArrayOf(1)), periodMillis = 0L)
        }
    }

    // ---- Malformed equality ---------------------------------------------

    @Test
    fun `Malformed compares offending bytes structurally, not by reference`() {
        val a = DecodeEvent.Malformed("bad len", byteArrayOf(1, 2, 3))
        val b = DecodeEvent.Malformed("bad len", byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Malformed distinguishes different bytes and reasons`() {
        val base = DecodeEvent.Malformed("bad len", byteArrayOf(1, 2, 3))
        assertNotEquals(base, DecodeEvent.Malformed("bad len", byteArrayOf(1, 2, 4)))
        assertNotEquals(base, DecodeEvent.Malformed("bad crc", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `Malformed handles a null byte payload on both sides`() {
        val nullBytes = DecodeEvent.Malformed("no payload", null)
        assertEquals(nullBytes, DecodeEvent.Malformed("no payload", null))
        assertNotEquals(nullBytes, DecodeEvent.Malformed("no payload", byteArrayOf()))
        assertNotEquals(DecodeEvent.Malformed("no payload", byteArrayOf()), nullBytes)
    }
}
