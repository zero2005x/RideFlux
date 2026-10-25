/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi2

import com.rideflux.domain.codec.DecodeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Regression coverage for the V11Y vendor-static real-time offsets.
 *
 * The offsets and scales below are transcribed from the reconstructed
 * `V11yRealtimeInfo.fromBytes` (vendor Dart AOT, arm64-v8a `libapp.so`
 * sha256 `feaaf7cf7fde39ba83a344307004e2071d0358948db24649a5e4b6920114b550`)
 * as recorded in
 * `handoffs/20261024-inmotion-i2-layout-successor4-20261025T0005/`:
 * voltage `0x00` s16 /100, current `0x02` s16 /100, charge current
 * `0x06` s16, speed `0x08` s16 /100, mileage `0x1c` u16, battery ratio
 * `0x22` u16 /10000. They are intentionally not read from the production
 * constants, so a wrong constant cannot make the test agree with itself.
 */
class InmotionI2RealtimeV11YTest {

    private fun ByteArray.putLe16(offset: Int, raw: Int) {
        this[offset] = (raw and 0xFF).toByte()
        this[offset + 1] = ((raw shr 8) and 0xFF).toByte()
    }

    /**
     * Hand-filled 74-byte body. `0x06` carries the charge current and
     * `0x1c` the mileage, so either old offset must show up as a wrong
     * value rather than a plausible one.
     */
    private fun syntheticBody(
        voltageRaw: Int = 7440,
        currentRaw: Int = 1250,
        chargeCurrentRaw: Int = -340,
        speedRaw: Int = 2530,
        mileageRaw: Int = 1234,
        batteryRaw: Int = 5000,
    ): ByteArray = ByteArray(InmotionI2RealtimeV11Y.MIN_DATA_SIZE).also {
        it.putLe16(0x00, voltageRaw)
        it.putLe16(0x02, currentRaw)
        it.putLe16(0x06, chargeCurrentRaw)
        it.putLe16(0x08, speedRaw)
        it.putLe16(0x1c, mileageRaw)
        it.putLe16(0x22, batteryRaw)
    }

    private fun buildFrame(cmd: Int, data: ByteArray): ByteArray {
        val body = byteArrayOf(InmotionI2Codec.FLAGS_DEFAULT.toByte(), (data.size + 1).toByte(), cmd.toByte()) + data
        return byteArrayOf(InmotionI2Codec.PREAMBLE_BYTE, InmotionI2Codec.PREAMBLE_BYTE) +
            InmotionI2Codec.escape(body) +
            byteArrayOf(InmotionI2Codec.xorChecksum8(body).toByte())
    }

    private fun mainInfo(selector: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        buildFrame(
            InmotionI2CommandBuilder.CMD_MAIN_INFO,
            byteArrayOf(selector.toByte()) + payload,
        )

    private fun version(major: Int, minor: Int): ByteArray {
        val data = ByteArray(25)
        data[0] = InmotionI2CommandBuilder.MAIN_INFO_VERSION.toByte()
        data[13] = minor.toByte()
        data[14] = major.toByte()
        return buildFrame(InmotionI2CommandBuilder.CMD_MAIN_INFO, data)
    }

    private fun identify(
        codec: InmotionI2WheelCodec,
        state: InmotionI2WheelCodec.InmotionI2State,
        model: String,
        major: Int? = null,
        minor: Int? = null,
    ) {
        codec.decode(state, mainInfo(InmotionI2CommandBuilder.MAIN_INFO_CAR_TYPE, model.toByteArray(Charsets.US_ASCII)))
        if (major != null && minor != null) codec.decode(state, version(major, minor))
    }

    private fun InmotionI2WheelCodec.telemetry(
        state: InmotionI2WheelCodec.InmotionI2State,
        body: ByteArray,
    ) = decode(state, buildFrame(InmotionI2CommandBuilder.CMD_REALTIME, body))
        .filterIsInstance<DecodeEvent.TelemetryUpdate>()
        .map { it.snapshot }

    @Test
    fun `hand-filled body reads voltage current speed and battery at the reconstructed offsets`() {
        val parsed = InmotionI2RealtimeV11Y.parse(syntheticBody())

        assertEquals(25.3, parsed.speedKmh, 0.0001)
        assertEquals(74.4, parsed.voltageV, 0.0001)
        assertEquals(12.5, parsed.currentA, 0.0001)
        val battery = parsed.batteryPercent!!
        assertEquals(50.0f, battery, 0.0001f)

        // The old offsets would yield -3.4 km/h and 12.34 %; the old
        // divisor would yield 148.8 V. None may appear.
        assertTrue("speed must not come from 0x06", abs(parsed.speedKmh + 3.4) > 0.0001)
        assertTrue("battery must not come from 0x1c", abs(battery - 12.34f) > 0.0001f)
        assertTrue("voltage must not use the /50 divisor", abs(parsed.voltageV - 148.8) > 0.0001)
    }

    @Test
    fun `codec path publishes the same corrected values`() {
        val codec = InmotionI2WheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State
        identify(codec, state, "V11Y", major = 1, minor = 3)

        val telemetry = codec.telemetry(state, syntheticBody()).single()

        assertEquals(74.4f, telemetry.voltageV!!, 0.0001f)
        assertEquals(12.5f, telemetry.currentA!!, 0.0001f)
        assertEquals(25.3f, telemetry.speedKmh!!, 0.0001f)
        assertEquals(50.0f, telemetry.batteryPercent!!, 0.0001f)
    }

    @Test
    fun `73-byte body is rejected by the parser and never decoded as V11Y by the codec`() {
        assertThrows(IllegalArgumentException::class.java) {
            InmotionI2RealtimeV11Y.parse(syntheticBody().copyOf(InmotionI2RealtimeV11Y.MIN_DATA_SIZE - 1))
        }

        val codec = InmotionI2WheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State
        identify(codec, state, "V11Y", major = 1, minor = 3)
        val telemetry = codec.telemetry(state, syntheticBody().copyOf(73))

        assertTrue(
            "a 73-byte body must not publish V11Y speed/battery: $telemetry",
            telemetry.all { it.speedKmh == null && it.batteryPercent == null },
        )
    }

    @Test
    fun `only a full record is a fresh speed sample for the interlock`() {
        val codec = InmotionI2WheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State
        identify(codec, state, "V11Y", major = 1, minor = 3)

        fun update(body: ByteArray) = codec.decode(state, buildFrame(InmotionI2CommandBuilder.CMD_REALTIME, body))
            .filterIsInstance<DecodeEvent.TelemetryUpdate>().single()

        val full = update(syntheticBody())
        assertEquals(25.3f, full.snapshot.speedKmh!!, 0.0001f)
        assertTrue("a decoded 74-byte record carries a fresh speed", full.freshSpeed)

        // A short record after it only re-sends the earlier speed.
        val short = update(syntheticBody().copyOf(73))
        assertTrue("a 73-byte record decodes no speed", !short.freshSpeed)
    }

    @Test
    fun `battery ratio is null above 10000 and maps the exact boundaries`() {
        assertEquals(0.0f, InmotionI2RealtimeV11Y.parse(syntheticBody(batteryRaw = 0)).batteryPercent!!, 0.0001f)
        assertEquals(100.0f, InmotionI2RealtimeV11Y.parse(syntheticBody(batteryRaw = 10000)).batteryPercent!!, 0.0001f)
        assertNull(InmotionI2RealtimeV11Y.parse(syntheticBody(batteryRaw = 10001)).batteryPercent)
        assertNull(InmotionI2RealtimeV11Y.parse(syntheticBody(batteryRaw = 65535)).batteryPercent)
    }

    @Test
    fun `signed voltage current and speed keep two's-complement semantics`() {
        val low = InmotionI2RealtimeV11Y.parse(
            syntheticBody(voltageRaw = 0x8000, currentRaw = 0xFFFF, speedRaw = 0x8000),
        )
        assertEquals(-327.68, low.voltageV, 0.0001)
        assertEquals(-0.01, low.currentA, 0.0001)
        assertEquals(-327.68, low.speedKmh, 0.0001)

        val high = InmotionI2RealtimeV11Y.parse(
            syntheticBody(voltageRaw = 0xFFFF, currentRaw = 0x8000, speedRaw = 0xFFFF),
        )
        assertEquals(-0.01, high.voltageV, 0.0001)
        assertEquals(-327.68, high.currentA, 0.0001)
        assertEquals(-0.01, high.speedKmh, 0.0001)

        // An unsigned re-read would publish 327.68 / 655.35 instead.
        assertTrue(abs(low.voltageV - 327.68) > 0.0001)
        assertTrue(abs(high.voltageV - 655.35) > 0.0001)
    }

    @Test
    fun `another model or unknown firmware does not run the V11Y parser`() {
        val v12Codec = InmotionI2WheelCodec("AA:BB:CC:DD:EE:FF")
        val v12State = v12Codec.newState() as InmotionI2WheelCodec.InmotionI2State
        identify(v12Codec, v12State, "V12", major = 2, minor = 0)
        val v12 = v12Codec.telemetry(v12State, syntheticBody()).single()
        // Only the family-core fields are established for V12; speed and
        // battery stay unknown instead of borrowing the V11Y layout.
        assertEquals(74.4f, v12.voltageV!!, 0.0001f)
        assertEquals(12.5f, v12.currentA!!, 0.0001f)
        assertNull(v12.speedKmh)
        assertNull(v12.batteryPercent)

        val v11Codec = InmotionI2WheelCodec("AA:BB:CC:DD:EE:FF")
        val v11State = v11Codec.newState() as InmotionI2WheelCodec.InmotionI2State
        identify(v11Codec, v11State, "V11", major = 1, minor = 3)
        val v11 = v11Codec.telemetry(v11State, syntheticBody()).single()
        assertTrue("V11 early values must not be V11Y values", v11.speedKmh != 25.3f && v11.batteryPercent != 50.0f)

        val unverifiedCodec = InmotionI2WheelCodec("AA:BB:CC:DD:EE:FF")
        val unverifiedState = unverifiedCodec.newState() as InmotionI2WheelCodec.InmotionI2State
        identify(unverifiedCodec, unverifiedState, "V11Y")
        val unverified = unverifiedCodec.telemetry(unverifiedState, syntheticBody())
        assertTrue(
            "unknown firmware must not publish V11Y layout fields: $unverified",
            unverified.all { it.speedKmh == null && it.batteryPercent == null },
        )
    }
}
