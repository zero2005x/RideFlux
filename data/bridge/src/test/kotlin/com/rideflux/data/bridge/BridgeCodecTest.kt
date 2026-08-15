/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeCodecTest {

    @Test
    fun `v2 frame is exactly 20 bytes and starts with magic and version`() {
        val bytes = BridgeCodec.encode(fullFrame())
        assertEquals(BridgeProtocol.FRAME_SIZE_V2, bytes.size)
        assertEquals(BridgeProtocol.MAGIC, bytes[0])
        assertEquals(BridgeProtocol.PROTOCOL_VERSION, bytes[1])
    }

    @Test
    fun `v2 round trip preserves every field`() {
        val original = BridgeFrame(
            timestampMillis = 1_700_000_000_000L,
            speedKmh = 18.3f,
            vehicleBatteryPercent = 75f,
            phoneBatteryPercent = 63,
            voltageV = 100.8f,
            tripDistanceMetres = 1_234_567,
            tripDurationSeconds = 4_321L,
            signal = SignalLevel.WEAK,
            stale = true,
            ready = true,
        )
        val decoded = BridgeCodec.decode(BridgeCodec.encode(original))
        assertEquals(original.timestampMillis, decoded?.timestampMillis)
        assertEquals(original.speedKmh, decoded?.speedKmh)
        assertEquals(original.vehicleBatteryPercent, decoded?.vehicleBatteryPercent)
        assertEquals(original.phoneBatteryPercent, decoded?.phoneBatteryPercent)
        assertEquals(original.voltageV, decoded?.voltageV)
        assertEquals(original.tripDistanceMetres, decoded?.tripDistanceMetres)
        assertEquals(original.tripDurationSeconds, decoded?.tripDurationSeconds)
        assertEquals(original.signal, decoded?.signal)
        assertEquals(true, decoded?.stale)
        assertEquals(true, decoded?.ready)
    }

    @Test
    fun `v2 timestamp is truncated to whole seconds and decoded unsigned`() {
        val original = fullFrame().copy(timestampMillis = 1_700_000_000_999L)
        val decoded = BridgeCodec.decode(BridgeCodec.encode(original))
        assertEquals(1_700_000_000_000L, decoded?.timestampMillis)

        // High-bit seconds (post-2038) must not sign-extend.
        val bytes = BridgeCodec.encode(original)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(3, 0x8000_0000.toInt())
        val future = BridgeCodec.decode(bytes)
        assertEquals(Integer.toUnsignedLong(0x8000_0000.toInt()) * 1_000L, future?.timestampMillis)
    }

    @Test
    fun `v2 absent fields round trip as null`() {
        val original = BridgeFrame(
            timestampMillis = 1_700_000_000_000L,
            speedKmh = null,
            vehicleBatteryPercent = null,
            phoneBatteryPercent = null,
            voltageV = null,
            tripDistanceMetres = null,
            tripDurationSeconds = null,
            signal = SignalLevel.NONE,
            stale = true,
            ready = false,
        )
        val decoded = BridgeCodec.decode(BridgeCodec.encode(original))
        assertNull(decoded?.speedKmh)
        assertNull(decoded?.vehicleBatteryPercent)
        assertNull(decoded?.phoneBatteryPercent)
        assertNull(decoded?.voltageV)
        assertNull(decoded?.tripDistanceMetres)
        assertNull(decoded?.tripDurationSeconds)
    }

    @Test
    fun `v2 distance beyond the u24 range encodes as absent`() {
        val inRange = fullFrame().copy(tripDistanceMetres = 0x00FF_FFFE)
        assertEquals(0x00FF_FFFE, BridgeCodec.decode(BridgeCodec.encode(inRange))?.tripDistanceMetres)

        // BridgeFrame guards reject negative values, but a distance past
        // the 24-bit ceiling is constructible and must not wrap around.
        val overflow = fullFrame().copy(tripDistanceMetres = 0x0100_0000)
        assertNull(BridgeCodec.decode(BridgeCodec.encode(overflow))?.tripDistanceMetres)
    }

    @Test
    fun `v2 rejects unknown signal bits`() {
        val bytes = BridgeCodec.encode(fullFrame())
        bytes[2] = (0x03 shl 2).toByte() // signal wire = 3
        assertNull(BridgeCodec.decode(bytes))
    }

    @Test
    fun `v2 out-of-range speed and voltage encode as absent`() {
        // BridgeFrame guards reject non-finite values outright; the
        // codec's defensive path is exercised with values that pass the
        // guard but overflow the i16 0.1-unit slots.
        val speedOverflow = fullFrame().copy(speedKmh = 40_000f)
        assertNull(BridgeCodec.decode(BridgeCodec.encode(speedOverflow))?.speedKmh)

        val voltageOverflow = fullFrame().copy(voltageV = 40_000f)
        assertNull(BridgeCodec.decode(BridgeCodec.encode(voltageOverflow))?.voltageV)
    }

    @Test
    fun `wrong sizes magic or version are rejected`() {
        val valid = BridgeCodec.encode(fullFrame())

        assertNull(BridgeCodec.decode(valid.copyOf(19))) // truncated
        assertNull(BridgeCodec.decode(valid + byteArrayOf(0))) // oversized
        assertNull(BridgeCodec.decode(ByteArray(20))) // zero magic/version

        val wrongMagic = valid.copyOf().also { it[0] = 0x53 }
        assertNull(BridgeCodec.decode(wrongMagic))

        val wrongVersion = valid.copyOf().also { it[1] = 3 }
        assertNull(BridgeCodec.decode(wrongVersion))
    }

    @Test
    fun `v1 frames remain decodable for mixed-install upgrades`() {
        val v1 = v1Bytes(
            timestampMillis = 1_700_000_000_123L,
            speedKmh = 18.3f,
            vehicleBatteryPercent = 75f,
            phoneBatteryPercent = 63,
            voltageV = 100.8f,
            tripDistanceMetres = 1_234_567,
            tripDurationSeconds = 4_321L,
            signal = SignalLevel.WEAK,
            stale = true,
            ready = true,
        )
        val decoded = BridgeCodec.decode(v1)
        assertEquals(1_700_000_000_123L, decoded?.timestampMillis)
        assertEquals(18.3f, decoded?.speedKmh)
        assertEquals(75f, decoded?.vehicleBatteryPercent)
        assertEquals(63, decoded?.phoneBatteryPercent)
        assertEquals(100.8f, decoded?.voltageV)
        assertEquals(1_234_567, decoded?.tripDistanceMetres)
        assertEquals(4_321L, decoded?.tripDurationSeconds)
        assertEquals(SignalLevel.WEAK, decoded?.signal)
        assertEquals(true, decoded?.stale)
        assertEquals(true, decoded?.ready)
    }

    @Test
    fun `v1 frame with non-zero reserved bytes is rejected`() {
        val v1 = v1Bytes(
            timestampMillis = 1_700_000_000_000L,
            speedKmh = null,
            vehicleBatteryPercent = null,
            phoneBatteryPercent = null,
            voltageV = null,
            tripDistanceMetres = null,
            tripDurationSeconds = null,
            signal = SignalLevel.NONE,
            stale = false,
            ready = false,
        )
        v1[30] = 0x01
        assertNull(BridgeCodec.decode(v1))
    }

    @Test
    fun `empty bridge frame placeholder encodes and decodes`() {
        val decoded = BridgeCodec.decode(BridgeCodec.encode(BridgeFrame.EMPTY))
        assertEquals(BridgeFrame.EMPTY.timestampMillis, decoded?.timestampMillis)
        assertEquals(SignalLevel.NONE, decoded?.signal)
        assertEquals(false, decoded?.ready)
        assertTrue(decoded?.speedKmh == null)
    }

    @Test
    fun `encode is deterministic byte-for-byte`() {
        val frame = fullFrame()
        assertArrayEquals(BridgeCodec.encode(frame), BridgeCodec.encode(frame))
    }

    private fun fullFrame() = BridgeFrame(
        timestampMillis = 1_700_000_000_000L,
        speedKmh = 18.3f,
        vehicleBatteryPercent = 75f,
        phoneBatteryPercent = 63,
        voltageV = 100.8f,
        tripDistanceMetres = 1_234_567,
        tripDurationSeconds = 4_321L,
        signal = SignalLevel.WEAK,
        stale = true,
        ready = true,
    )

    /** Hand-builds a legacy 32-byte v1 frame, since the encoder no longer emits it. */
    private fun v1Bytes(
        timestampMillis: Long,
        speedKmh: Float?,
        vehicleBatteryPercent: Float?,
        phoneBatteryPercent: Int?,
        voltageV: Float?,
        tripDistanceMetres: Int?,
        tripDurationSeconds: Long?,
        signal: SignalLevel,
        stale: Boolean,
        ready: Boolean,
    ): ByteArray {
        val out = ByteArray(32)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x52.toByte())
        buf.put(1.toByte())
        var flags = 0
        if (stale) flags = flags or 0x01
        if (ready) flags = flags or 0x02
        buf.put(flags.toByte())
        buf.put(signal.wire.toByte())
        buf.putInt((timestampMillis / 1_000L).toInt())
        buf.putInt((timestampMillis % 1_000L).toInt())
        buf.putShort(speedKmh?.let { (it * 10f).toInt().toShort() } ?: BridgeProtocol.INT16_NULL)
        buf.put(
            vehicleBatteryPercent
                ?.takeIf { it.isFinite() && it in 0f..100f }
                ?.let { it.toInt().toByte() }
                ?: BridgeProtocol.PERCENT_NULL.toByte(),
        )
        buf.put(
            phoneBatteryPercent
                ?.takeIf { it in 0..100 }
                ?.let { it.toByte() }
                ?: BridgeProtocol.PERCENT_NULL.toByte(),
        )
        buf.putShort(voltageV?.let { (it * 10f).toInt().toShort() } ?: BridgeProtocol.INT16_NULL)
        buf.putInt(tripDistanceMetres ?: BridgeProtocol.INT32_NULL)
        buf.putLong(tripDurationSeconds ?: Long.MIN_VALUE)
        buf.putShort(0.toShort())
        return out
    }
}
