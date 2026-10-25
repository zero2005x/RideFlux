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
    fun `invalid numeric payloads are rejected without throwing`() {
        val badBattery = BridgeCodec.encode(fullFrame()).also { it[9] = 101 }
        assertNull(BridgeCodec.decode(badBattery))
        val badSpeed = BridgeCodec.encode(fullFrame()).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(7, (-1).toShort())
        }
        assertNull(BridgeCodec.decode(badSpeed))
        val badDuration = BridgeCodec.encode(fullFrame()).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16, -1)
        }
        assertNull(BridgeCodec.decode(badDuration))
    }

    @Test
    fun `single byte corruption cannot escape either decoder`() {
        val legacy = ByteArray(32).also { it[0] = BridgeProtocol.MAGIC; it[1] = 1 }
        for (valid in listOf(BridgeCodec.encode(fullFrame()), legacy)) {
            for (offset in valid.indices) {
                for (value in 0..255) {
                    BridgeCodec.decode(valid.copyOf().also { it[offset] = value.toByte() })
                }
            }
        }
    }

    @Test
    fun `duration overflow becomes unknown rather than wrapping`() {
        val original = fullFrame().copy(tripDurationSeconds = Long.MAX_VALUE)
        val decoded = BridgeCodec.decode(BridgeCodec.encode(original))
        assertTrue(decoded != null)
        assertNull(decoded?.tripDurationSeconds)
    }

    @Test
    fun `legacy millisecond remainder must be within one second`() {
        val legacy = ByteArray(32).also { it[0] = BridgeProtocol.MAGIC; it[1] = 1 }
        ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 1_000)
        assertNull(BridgeCodec.decode(legacy))
    }

    @Test
    fun `v3 golden bytes pin the layout`() {
        val expected = byteArrayOf(
            0x52, 0x03, 0x27, // magic, version 3, flags: stale|ready|WEAK|WHEEL
            0x00, 0xF1.toByte(), 0x53, 0x65, // timestamp seconds 1 700 000 000, u32 LE
            0xB7.toByte(), 0x00, // speed 18.3 -> 183
            0x4B, 0x3F, // vehicle 75 %, phone 63 %
            0xF0.toByte(), 0x03, // voltage 100.8 -> 1008
            0x87.toByte(), 0xD6.toByte(), 0x12, // distance 1 234 567, u24 LE
            0xE1.toByte(), 0x10, 0x00, 0x00, // duration 4321 s, i32 LE
            0x7B, 0x00, // aux1 12.3 -> 123
            0x3D, // aux2 61
            0x69, // aux3 105
        )
        assertArrayEquals(expected, BridgeCodec.encode(fullFrame()))
    }

    @Test
    fun `v3 frame is exactly 24 bytes and starts with magic and version`() {
        val bytes = BridgeCodec.encode(fullFrame())
        assertEquals(BridgeProtocol.FRAME_SIZE_V3, bytes.size)
        assertEquals(BridgeProtocol.MAGIC, bytes[0])
        assertEquals(BridgeProtocol.PROTOCOL_VERSION, bytes[1])
    }

    @Test
    fun `v3 round trip preserves the vehicle type and aux slots`() {
        val wheel = fullFrame().copy(
            vehicleType = VehicleType.WHEEL,
            aux1 = 12.3f,
            aux2 = 61,
            aux3 = 105,
        )
        val decodedWheel = BridgeCodec.decode(BridgeCodec.encode(wheel))
        assertEquals(VehicleType.WHEEL, decodedWheel?.vehicleType)
        assertEquals(12.3f, decodedWheel?.aux1)
        assertEquals(61, decodedWheel?.aux2)
        assertEquals(105, decodedWheel?.aux3)

        val scooter = fullFrame().copy(
            vehicleType = VehicleType.SCOOTER,
            aux1 = -4.5f,
            aux2 = 2,
            aux3 = 41,
        )
        val decodedScooter = BridgeCodec.decode(BridgeCodec.encode(scooter))
        assertEquals(VehicleType.SCOOTER, decodedScooter?.vehicleType)
        assertEquals(-4.5f, decodedScooter?.aux1)
        assertEquals(2, decodedScooter?.aux2)
        assertEquals(41, decodedScooter?.aux3)

        // A BMS has no speed and only one meaningful aux today, so the
        // empty slots must survive the round trip as absent.
        val bms = fullFrame().copy(
            vehicleType = VehicleType.BMS,
            speedKmh = null,
            aux1 = -4.2f,
            aux2 = null,
            aux3 = null,
        )
        val decodedBms = BridgeCodec.decode(BridgeCodec.encode(bms))
        assertEquals(VehicleType.BMS, decodedBms?.vehicleType)
        assertEquals(-4.2f, decodedBms?.aux1)
        assertNull(decodedBms?.speedKmh)
        assertNull(decodedBms?.aux2)
        assertNull(decodedBms?.aux3)
    }

    @Test
    fun `a v3 frame truncated to the v2 length is rejected`() {
        val truncated = BridgeCodec.encode(fullFrame()).copyOf(BridgeProtocol.FRAME_SIZE_V2)
        assertNull(BridgeCodec.decode(truncated))
    }

    @Test
    fun `a v2 frame still decodes but reports no vehicle type and no aux`() {
        // The first 20 bytes are shared, so a v3 frame's prefix relabelled
        // as version 2 is a valid legacy frame.
        val legacy = BridgeCodec.encode(fullFrame()).copyOf(BridgeProtocol.FRAME_SIZE_V2)
            .also { it[1] = 2 }
        val decoded = BridgeCodec.decode(legacy)
        assertEquals(VehicleType.UNKNOWN, decoded?.vehicleType)
        assertNull(decoded?.aux1)
        assertNull(decoded?.aux2)
        assertNull(decoded?.aux3)
        assertEquals(18.3f, decoded?.speedKmh)
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
        assertNull(decoded?.aux1)
        assertNull(decoded?.aux2)
        assertNull(decoded?.aux3)
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

        val wrongVersion = valid.copyOf().also { it[1] = 4 }
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

    /**
     * Hand-written scooter frame. Every byte is computed from the documented
     * layout rather than from `encode`, so a wrong offset in the encoder
     * cannot also move the expectation.
     */
    @Test
    fun `v3 scooter golden bytes pin the category slots`() {
        val frame = BridgeFrame(
            timestampMillis = 1_700_000_000_000L,
            speedKmh = 12.5f,
            vehicleBatteryPercent = 55f,
            phoneBatteryPercent = 90,
            voltageV = null,
            tripDistanceMetres = null,
            tripDurationSeconds = null,
            signal = SignalLevel.GOOD,
            stale = false,
            ready = true,
            vehicleType = VehicleType.SCOOTER,
            aux1 = -4.5f,
            aux2 = 2,
            aux3 = 41,
        )
        val expected = byteArrayOf(
            0x52, 0x03, 0x4A, // magic, v3, flags: ready|GOOD|SCOOTER
            0x00, 0xF1.toByte(), 0x53, 0x65, // timestamp 1 700 000 000 s, u32 LE
            0x7D, 0x00, // speed 12.5 -> 125
            0x37, 0x5A, // vehicle 55 %, phone 90 %
            0x00, 0x80.toByte(), // voltage absent (INT16_NULL)
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // distance absent (U24_NULL)
            0x00, 0x00, 0x00, 0x80.toByte(), // duration absent (INT32_NULL)
            0xD3.toByte(), 0xFF.toByte(), // aux1 -4.5 -> -45
            0x02, // aux2 gear 2
            0x29, // aux3 throttle 41 %
        )
        assertArrayEquals(expected, BridgeCodec.encode(frame))
    }

    /**
     * Hand-written BMS frame: no speed, no batteries, no voltage, and only
     * `aux1` present. The reserved 0xFF bytes must decode back to null.
     */
    @Test
    fun `v3 BMS golden bytes keep every absent slot absent`() {
        val frame = BridgeFrame(
            timestampMillis = 1_700_000_123_000L,
            speedKmh = null,
            vehicleBatteryPercent = null,
            phoneBatteryPercent = null,
            voltageV = null,
            tripDistanceMetres = null,
            tripDurationSeconds = null,
            signal = SignalLevel.NONE,
            stale = false,
            ready = true,
            vehicleType = VehicleType.BMS,
            aux1 = -4.2f,
            aux2 = null,
            aux3 = null,
        )
        val expected = byteArrayOf(
            0x52, 0x03, 0x62, // magic, v3, flags: ready|NONE|BMS
            0x7B, 0xF1.toByte(), 0x53, 0x65, // timestamp 1 700 000 123 s
            0x00, 0x80.toByte(), // speed absent
            0xFF.toByte(), 0xFF.toByte(), // both batteries absent
            0x00, 0x80.toByte(), // voltage absent
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // distance absent
            0x00, 0x00, 0x00, 0x80.toByte(), // duration absent
            0xD6.toByte(), 0xFF.toByte(), // aux1 -4.2 -> -42
            0xFF.toByte(), 0xFF.toByte(), // aux2/aux3 reserved
        )
        assertArrayEquals(expected, BridgeCodec.encode(frame))

        val decoded = BridgeCodec.decode(expected)!!
        assertNull(decoded.speedKmh)
        assertNull(decoded.vehicleBatteryPercent)
        assertNull(decoded.voltageV)
        assertNull(decoded.tripDistanceMetres)
        assertNull(decoded.aux2)
        assertNull(decoded.aux3)
        assertEquals(VehicleType.BMS, decoded.vehicleType)
        assertEquals(-4.2f, decoded.aux1)
    }

    @Test
    fun `a v3 frame truncated to twenty bytes is rejected, not rendered`() {
        val full = BridgeCodec.encode(fullFrame())

        assertEquals(BridgeProtocol.FRAME_SIZE_V3, full.size)
        assertNull(BridgeCodec.decode(full.copyOf(BridgeProtocol.FRAME_SIZE_V2)))
    }

    @Test
    fun `an aux code above the reserved sentinel is refused at construction`() {
        val base = fullFrame()
        for (bad in listOf(255, 300)) {
            try {
                base.copy(aux2 = bad)
                throw AssertionError("aux2=$bad should have been rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("254"))
            }
        }
    }

    @Test
    fun `a wheel aux3 carries the biased MOS temperature as a plain code`() {
        // 105 on the wire is 41 °C once the producer's +64 bias is removed;
        // the codec itself stays bias-agnostic and round-trips the code.
        val decoded = BridgeCodec.decode(BridgeCodec.encode(fullFrame()))!!

        assertEquals(105, decoded.aux3)
        assertEquals(VehicleType.WHEEL, decoded.vehicleType)
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
        vehicleType = VehicleType.WHEEL,
        aux1 = 12.3f,
        aux2 = 61,
        aux3 = 105,
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
