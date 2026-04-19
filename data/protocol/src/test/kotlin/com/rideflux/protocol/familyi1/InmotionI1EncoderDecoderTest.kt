/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi1

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InmotionI1EncoderDecoderTest {

    // ---- Standard CAN-envelope round-trips ------------------------------

    @Test fun buildStandard_producesRoundTrippableFrame() {
        val data8 = hex("B2 00 00 00 11 00 00 00") // beep per §10.4.2
        val wire = InmotionI1CommandBuilder.buildStandard(
            canId = InmotionI1CommandBuilder.CAN_ID_REMOTE_CONTROL,
            data8 = data8,
        )
        assertEquals(0xAA.toByte(), wire[0])
        assertEquals(0xAA.toByte(), wire[1])
        assertEquals(0x55.toByte(), wire[wire.size - 2])
        assertEquals(0x55.toByte(), wire[wire.size - 1])

        val result = InmotionI1Decoder.decode(wire)
        assertTrue(result is InmotionI1DecodeResult.Ok)
        val ok = result as InmotionI1DecodeResult.Ok
        assertEquals(wire.size, ok.consumedBytes)
        assertEquals(InmotionI1CommandBuilder.CAN_ID_REMOTE_CONTROL, ok.frame.canId)
        assertArrayEquals(data8, ok.frame.data8)
        assertEquals(0x08, ok.frame.lenByte)
        assertEquals(0x05, ok.frame.chan)
        assertEquals(0x00, ok.frame.fmt)
        assertEquals(0x00, ok.frame.type)
        assertEquals(0, ok.frame.exData.size)
    }

    @Test fun requestLiveTelemetry_isRemoteFrameWithAllOnesData() {
        val wire = InmotionI1CommandBuilder.requestLiveTelemetry()
        val ok = InmotionI1Decoder.decode(wire) as InmotionI1DecodeResult.Ok
        assertEquals(InmotionI1CommandBuilder.CAN_ID_LIVE_TELEMETRY, ok.frame.canId)
        assertArrayEquals(hex("FF FF FF FF FF FF FF FF"), ok.frame.data8)
        assertEquals(0x01, ok.frame.type) // remote
    }

    // ---- Command-builder byte-map sanity (§10.4) ------------------------

    @Test fun setMaxSpeed_encodesBigEndianAtOffsets3And4() {
        // 38.5 km/h × 1000 = 38 500 = 0x9664
        val wire = InmotionI1CommandBuilder.setMaxSpeed(38.5)
        val ok = InmotionI1Decoder.decode(wire) as InmotionI1DecodeResult.Ok
        assertEquals(0x01.toByte(), ok.frame.data8[0])
        assertEquals(0x96.toByte(), ok.frame.data8[3])
        assertEquals(0x64.toByte(), ok.frame.data8[4])
        assertEquals(InmotionI1CommandBuilder.CAN_ID_RIDE_MODE, ok.frame.canId)
    }

    @Test fun setVolume_encodesVolumeTimes100LittleEndian() {
        val wire = InmotionI1CommandBuilder.setVolume(100)
        val ok = InmotionI1Decoder.decode(wire) as InmotionI1DecodeResult.Ok
        // 100 × 100 = 10 000 = 0x2710
        assertEquals(0x10.toByte(), ok.frame.data8[0])
        assertEquals(0x27.toByte(), ok.frame.data8[1])
        assertEquals(InmotionI1CommandBuilder.CAN_ID_VOLUME, ok.frame.canId)
    }

    @Test fun calibration_hasMagicConstant() {
        val wire = InmotionI1CommandBuilder.calibration()
        val ok = InmotionI1Decoder.decode(wire) as InmotionI1DecodeResult.Ok
        assertArrayEquals(hex("32 54 76 98 00 00 00 00"), ok.frame.data8)
    }

    @Test fun sendPin_encodesAsciiDigits() {
        val wire = InmotionI1CommandBuilder.sendPin("123456")
        val ok = InmotionI1Decoder.decode(wire) as InmotionI1DecodeResult.Ok
        assertEquals('1'.code.toByte(), ok.frame.data8[0])
        assertEquals('6'.code.toByte(), ok.frame.data8[5])
        assertEquals(0x00.toByte(), ok.frame.data8[6])
        assertEquals(0x00.toByte(), ok.frame.data8[7])
    }

    // ---- Preamble / checksum / trailer failures -------------------------

    @Test fun decode_failsOnBadPreamble() {
        val wire = InmotionI1CommandBuilder.requestLiveTelemetry().copyOf()
        wire[0] = 0x00
        val result = InmotionI1Decoder.decode(wire)
        assertTrue(result is InmotionI1DecodeResult.Fail)
        assertEquals(InmotionI1DecodeError.BadPreamble, (result as InmotionI1DecodeResult.Fail).error)
    }

    @Test fun decode_failsOnMutatedBody() {
        val wire = InmotionI1CommandBuilder.setVolume(50).copyOf()
        // Tamper a body byte after escape; offset 2 is the first body byte
        // (CAN-ID LSB). Flipping it invalidates the checksum.
        wire[2] = (wire[2].toInt() xor 0x01).toByte()
        val result = InmotionI1Decoder.decode(wire)
        assertTrue(result is InmotionI1DecodeResult.Fail)
        assertTrue((result as InmotionI1DecodeResult.Fail).error is InmotionI1DecodeError.BadChecksum)
    }

    @Test fun decode_failsOnBadTrailer() {
        val wire = InmotionI1CommandBuilder.requestLiveTelemetry().copyOf()
        wire[wire.size - 1] = 0x00
        val result = InmotionI1Decoder.decode(wire)
        assertTrue(result is InmotionI1DecodeResult.Fail)
        assertEquals(InmotionI1DecodeError.BadTrailer, (result as InmotionI1DecodeResult.Fail).error)
    }

    // ---- Extended-frame synthesis (§2.6.4) ------------------------------

    /**
     * Synthesise an extended telemetry record by hand, then feed it
     * through the decoder. No authoritative TEST_VECTOR is available
     * for I1, so this is a self-consistent check.
     */
    @Test fun decode_parsesExtendedFrameWithExData() {
        val exLen = 80
        val body = ByteArray(16 + exLen)
        // CAN-ID (live telemetry, LE).
        val canId = InmotionI1CommandBuilder.CAN_ID_LIVE_TELEMETRY
        body[0] = (canId and 0xFF).toByte()
        body[1] = ((canId ushr 8) and 0xFF).toByte()
        body[2] = ((canId ushr 16) and 0xFF).toByte()
        body[3] = ((canId ushr 24) and 0xFF).toByte()
        // EX-LEN U32LE at unstuffed offset 4..7.
        body[4] = (exLen and 0xFF).toByte()
        body[5] = ((exLen ushr 8) and 0xFF).toByte()
        body[6] = 0; body[7] = 0
        // bytes 8..11 standard payload remainder (all 0 for the test).
        body[12] = 0xFE.toByte()
        body[13] = 0x05
        body[14] = 0x01 // FMT extended
        body[15] = 0x00 // data frame
        // Fill EX-DATA with a recognisable pattern that covers all
        // three byte values that must be escaped.
        for (i in 0 until exLen) body[16 + i] = (i and 0xFF).toByte()

        val check = InmotionI1Codec.checksum(body)
        val escaped = InmotionI1Codec.escape(body)
        val wire = ByteArray(2 + escaped.size + 1 + 2)
        wire[0] = 0xAA.toByte()
        wire[1] = 0xAA.toByte()
        System.arraycopy(escaped, 0, wire, 2, escaped.size)
        wire[2 + escaped.size] = check
        wire[2 + escaped.size + 1] = 0x55
        wire[2 + escaped.size + 2] = 0x55

        val ok = InmotionI1Decoder.decode(wire) as InmotionI1DecodeResult.Ok
        assertEquals(0xFE, ok.frame.lenByte)
        assertEquals(exLen, ok.frame.exData.size)
        assertEquals(wire.size, ok.consumedBytes)
    }

    // ---- Extended-telemetry parser (§3.5.2) -----------------------------

    @Test fun extendedTelemetry_parsesSpecifiedOffsets() {
        val ex = ByteArray(InmotionI1ExtendedTelemetry.MIN_EX_DATA_SIZE)
        // Voltage 84.25 V → 8425 = 0x20E9 at offset 24..27 (U32LE).
        ex[24] = 0xE9.toByte()
        ex[25] = 0x20
        ex[26] = 0; ex[27] = 0
        // Phase current -1200 (1/100 A = -12.00 A) at offset 20..23 (S32).
        // -1200 as U32 = 0xFFFFFB50.
        ex[20] = 0x50; ex[21] = 0xFB.toByte(); ex[22] = 0xFF.toByte(); ex[23] = 0xFF.toByte()
        // Temperatures 25 and -10 at offsets 32 and 34.
        ex[32] = 25
        ex[34] = (-10).toByte()
        // Trip distance 1234 m at offset 48..51 (U32LE).
        ex[48] = 0xD2.toByte(); ex[49] = 0x04; ex[50] = 0; ex[51] = 0

        val tel = InmotionI1ExtendedTelemetry.parse(ex)
        assertEquals(8425L, tel.voltageHundredthsV)
        assertEquals(84.25, tel.voltageV, 0.0001)
        assertEquals(-1200, tel.phaseCurrentHundredthsA)
        assertEquals(25, tel.temperature1Celsius)
        assertEquals(-10, tel.temperature2Celsius)
        assertEquals(1234L, tel.tripDistanceMetres)
        assertNotNull(tel.totalDistanceRaw8)
        assertEquals(8, tel.totalDistanceRaw8.size)
    }
}
