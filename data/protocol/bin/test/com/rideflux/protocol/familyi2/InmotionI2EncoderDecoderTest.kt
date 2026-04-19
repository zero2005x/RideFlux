/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi2

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InmotionI2EncoderDecoderTest {

    // ---- Round-trip the §9.5.2 identification requests ------------------

    @Test fun requestCarType_roundTrips() {
        val wire = InmotionI2CommandBuilder.requestCarType()
        val ok = InmotionI2Decoder.decode(wire) as InmotionI2DecodeResult.Ok
        assertEquals(wire.size, ok.consumedBytes)
        assertEquals(InmotionI2Codec.FLAGS_INIT, ok.frame.flags)
        assertEquals(InmotionI2CommandBuilder.CMD_MAIN_INFO, ok.frame.cmd)
        assertArrayEquals(byteArrayOf(0x01), ok.frame.data)
    }

    @Test fun requestRealtime_hasEmptyData() {
        val wire = InmotionI2CommandBuilder.requestRealtime()
        val ok = InmotionI2Decoder.decode(wire) as InmotionI2DecodeResult.Ok
        assertEquals(InmotionI2Codec.FLAGS_DEFAULT, ok.frame.flags)
        assertEquals(InmotionI2CommandBuilder.CMD_REALTIME, ok.frame.cmd)
        assertEquals(0, ok.frame.data.size)
    }

    // ---- §10.4.9 settings writes round-trip ------------------------------

    @Test fun setMaxSpeed_encodesBigEndian() {
        val wire = InmotionI2CommandBuilder.setMaxSpeed(38.5)
        val ok = InmotionI2Decoder.decode(wire) as InmotionI2DecodeResult.Ok
        assertEquals(InmotionI2CommandBuilder.CMD_CONTROL, ok.frame.cmd)
        // DATA = [subCmd=0x21, hi, lo] for 3850 = 0x0F0A
        assertArrayEquals(hex("21 0F 0A"), ok.frame.data)
    }

    @Test fun setMute_invertsSense() {
        // muted=true → sub 0x2C, value 0
        val wireMuted = InmotionI2CommandBuilder.setMute(true)
        val okMuted = InmotionI2Decoder.decode(wireMuted) as InmotionI2DecodeResult.Ok
        assertArrayEquals(hex("2C 00"), okMuted.frame.data)
        // muted=false → value 1
        val wireSound = InmotionI2CommandBuilder.setMute(false)
        val okSound = InmotionI2Decoder.decode(wireSound) as InmotionI2DecodeResult.Ok
        assertArrayEquals(hex("2C 01"), okSound.frame.data)
    }

    @Test fun setPedalSensitivity_duplicatesTheByte() {
        val wire = InmotionI2CommandBuilder.setPedalSensitivity(0x42)
        val ok = InmotionI2Decoder.decode(wire) as InmotionI2DecodeResult.Ok
        assertArrayEquals(hex("25 42 42"), ok.frame.data)
    }

    @Test fun calibration_hasThreeMagicBytes() {
        val wire = InmotionI2CommandBuilder.calibration()
        val ok = InmotionI2Decoder.decode(wire) as InmotionI2DecodeResult.Ok
        assertArrayEquals(hex("42 01 00 01"), ok.frame.data)
    }

    // ---- §2.7.1 high-bit mask on receive --------------------------------

    @Test fun decode_masksCmdHighBitAndReportsOriginal() {
        // Build a frame then tamper the raw CMD byte to set bit 7.
        val wire = InmotionI2CommandBuilder.requestRealtime().copyOf()
        // Body layout after preamble is flags(0x14), len(0x01), cmd(0x04).
        // Those three bytes are at wire offsets 2, 3, 4 (none need
        // escaping). Flip high bit of the CMD byte.
        wire[4] = (wire[4].toInt() or 0x80).toByte()
        // We must also recompute the XOR checksum because it covers CMD.
        // The CHECK byte is the LAST byte of the wire (no trailer in I2).
        wire[wire.size - 1] = (wire[wire.size - 1].toInt() xor 0x80).toByte()

        val ok = InmotionI2Decoder.decode(wire) as InmotionI2DecodeResult.Ok
        assertEquals(0x04, ok.frame.cmd)
        assertTrue(ok.frame.cmdRawHighBitSet)
    }

    @Test fun decode_reportsCleanHighBitWhenNotSet() {
        val wire = InmotionI2CommandBuilder.requestRealtime()
        val ok = InmotionI2Decoder.decode(wire) as InmotionI2DecodeResult.Ok
        assertFalse(ok.frame.cmdRawHighBitSet)
    }

    // ---- Checksum / framing failures ------------------------------------

    @Test fun decode_failsOnBadChecksum() {
        val wire = InmotionI2CommandBuilder.setVolume(40).copyOf()
        wire[wire.size - 1] = (wire[wire.size - 1].toInt() xor 0xFF).toByte()
        val fail = InmotionI2Decoder.decode(wire) as InmotionI2DecodeResult.Fail
        assertTrue(fail.error is InmotionI2DecodeError.BadChecksum)
    }

    @Test fun decode_failsOnBadFlags() {
        // Build a valid wire then rewrite FLAGS (offset 2) + CHECK.
        val wire = InmotionI2CommandBuilder.requestRealtime().copyOf()
        val oldFlags = wire[2].toInt() and 0xFF
        val newFlags = 0x15
        wire[2] = newFlags.toByte()
        wire[wire.size - 1] = (wire[wire.size - 1].toInt() xor oldFlags xor newFlags).toByte()
        val fail = InmotionI2Decoder.decode(wire) as InmotionI2DecodeResult.Fail
        assertTrue(fail.error is InmotionI2DecodeError.BadFlags)
    }

    // ---- Escape handling of 0xAA inside DATA ----------------------------

    @Test fun build_escapesAaInsideData() {
        // Craft a settings write whose first DATA byte is not-AA and
        // whose payload contains 0xAA — check that the wire has A5 AA
        // and that decoding recovers the original.
        val raw = byteArrayOf(0xAA.toByte(), 0x11)
        val wire = InmotionI2CommandBuilder.control(subCmd = 0x26, payload = raw)
        // There must be at least one A5 byte somewhere after the
        // preamble and before CHECK.
        var sawEscape = false
        for (i in 2 until wire.size - 1) {
            if (wire[i] == 0xA5.toByte()) { sawEscape = true; break }
        }
        assertTrue(sawEscape)
        val ok = InmotionI2Decoder.decode(wire) as InmotionI2DecodeResult.Ok
        assertArrayEquals(byteArrayOf(0x26, 0xAA.toByte(), 0x11), ok.frame.data)
    }

    // ---- §3.5.4.A telemetry parser --------------------------------------

    @Test fun realtimeV11Early_parsesOffsets() {
        val data = ByteArray(InmotionI2RealtimeV11Early.MIN_DATA_SIZE)
        // Voltage 84.25 V → 8425 = 0x20E9
        data[0] = 0xE9.toByte(); data[1] = 0x20
        // Current -500 (1/100 A) = 0xFE0C LE
        data[2] = 0x0C; data[3] = 0xFE.toByte()
        // Speed 25.00 km/h → 2500 = 0x09C4
        data[4] = 0xC4.toByte(); data[5] = 0x09
        // Torque 1.00 N·m → 100 = 0x0064
        data[6] = 0x64; data[7] = 0x00
        // Battery power 300 W = 0x012C
        data[8] = 0x2C; data[9] = 0x01
        // Motor power 250 W = 0x00FA
        data[10] = 0xFA.toByte(); data[11] = 0x00
        // Trip 500 → 500 × 10 = 5 000 m
        data[12] = 0xF4.toByte(); data[13] = 0x01
        // Remaining range 1 000 → 10 000 m
        data[14] = 0xE8.toByte(); data[15] = 0x03

        val tel = InmotionI2RealtimeV11Early.parse(data)
        assertEquals(8425, tel.voltageHundredthsV)
        assertEquals(-500, tel.phaseCurrentHundredthsA)
        assertEquals(2500, tel.speedHundredthsKmh)
        assertEquals(100, tel.torqueHundredthsNm)
        assertEquals(300, tel.batteryPowerWatts)
        assertEquals(250, tel.motorPowerWatts)
        assertEquals(5000, tel.tripDistanceMetres)
        assertEquals(10000, tel.remainingRangeMetres)
        assertEquals(25.0, tel.speedKmh, 0.001)
    }
}
