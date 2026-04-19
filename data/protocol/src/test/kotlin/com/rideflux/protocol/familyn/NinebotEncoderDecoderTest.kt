/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyn

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end encoder/decoder tests for Families N1 / N2.
 *
 * Anchored on the 禮5 (checksum derivation) and 禮6 (XOR round-trip)
 * vectors of `TEST_VECTORS.md`: the 禮5 vector is a complete N1 frame
 * once the `55 AA D4 FF` prefix/trailer are considered, and the 禮6
 * vector is the same frame encrypted for N2 with a non-zero 帠.
 */
class NinebotEncoderDecoderTest {

    private val gammaV6 = hex("01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10")

    // --- N1 frames (identity keystream) ------------------------------

    @Test fun `decode N1 frame built from 禮5 vector`() {
        // Post-prefix bytes: 03 09 01 10 0E D4 FF (禮5 + 禮6.1 checksum)
        val wire = hex("55 AA 03 09 01 10 0E D4 FF")
        val res = NinebotDecoder.decode(wire, NinebotFamily.N1)
        assertTrue("N1 禮5 vector must decode", res is NinebotDecodeResult.Ok)
        res as NinebotDecodeResult.Ok
        assertEquals(9, res.consumedBytes)
        val f = res.frame
        assertEquals(NinebotFamily.N1, f.family)
        assertEquals(0x09, f.src)
        assertEquals(0x01, f.dst)
        assertEquals(null, f.cmd)
        assertEquals(0x10, f.param)
        assertArrayEquals(byteArrayOf(0x0E), f.data)
    }

    @Test fun `encode N1 frame reproduces 禮5 wire bytes exactly`() {
        val frame = NinebotFrame(
            family = NinebotFamily.N1,
            src = 0x09, dst = 0x01, cmd = null, param = 0x10,
            data = byteArrayOf(0x0E),
        )
        val wire = NinebotCommandBuilder.build(frame)
        assertArrayEquals(hex("55 AA 03 09 01 10 0E D4 FF"), wire)
    }

    @Test fun `N1 round-trip preserves every field`() {
        val original = NinebotFrame(
            family = NinebotFamily.N1,
            src = 0x09, dst = 0x01, cmd = null, param = 0x22,
            data = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        )
        val wire = NinebotCommandBuilder.build(original)
        val decoded = NinebotDecoder.decode(wire, NinebotFamily.N1)
        assertTrue(decoded is NinebotDecodeResult.Ok)
        assertEquals(original, (decoded as NinebotDecodeResult.Ok).frame)
    }

    // --- N2 frames (non-zero keystream) ------------------------------

    @Test fun `decode N2 frame built from 禮6 wire bytes with 帠`() {
        // TEST_VECTORS.md 禮6 wire (after 55 AA): 03 08 03 13 0A D1 F9.
        // In N2 interpretation, the deobfuscated bytes
        // 03 09 01 10 0E D4 FF map to:
        //   LEN=03, SRC=09, DST=01, CMD=10, PARAM=0E,
        //   DATA=[], CHK=D4 FF.
        val wire = hex("55 AA 03 08 03 13 0A D1 F9")
        val res = NinebotDecoder.decode(wire, NinebotFamily.N2, gammaV6)
        assertTrue("N2 禮6 vector must decode with 帠", res is NinebotDecodeResult.Ok)
        res as NinebotDecodeResult.Ok
        val f = res.frame
        assertEquals(NinebotFamily.N2, f.family)
        assertEquals(0x09, f.src)
        assertEquals(0x01, f.dst)
        assertEquals(0x10, f.cmd)
        assertEquals(0x0E, f.param)
        assertEquals(0, f.data.size)
    }

    @Test fun `encode N2 frame with 帠 reproduces 禮6 wire exactly`() {
        val frame = NinebotFrame(
            family = NinebotFamily.N2,
            src = 0x09, dst = 0x01, cmd = 0x10, param = 0x0E,
            data = ByteArray(0),
        )
        val wire = NinebotCommandBuilder.build(frame, gammaV6)
        assertArrayEquals(hex("55 AA 03 08 03 13 0A D1 F9"), wire)
    }

    @Test fun `N2 round-trip under non-zero 帠 preserves every field`() {
        val gamma = ByteArray(16) { (it * 7 + 3).toByte() }
        val original = NinebotFrame(
            family = NinebotFamily.N2,
            src = NinebotCommandBuilder.ADDR_HOST_APP_DEFAULT,
            dst = NinebotCommandBuilder.ADDR_CONTROLLER,
            cmd = NinebotCommandBuilder.CMD_READ,
            param = NinebotCommandBuilder.PARAM_BATTERY_LEVEL,
            data = byteArrayOf(0x02, 0x55.toByte(), 0xAA.toByte(), 0x7F),
        )
        val wire = NinebotCommandBuilder.build(original, gamma)
        val res = NinebotDecoder.decode(wire, NinebotFamily.N2, gamma)
        assertTrue(res is NinebotDecodeResult.Ok)
        assertEquals(original, (res as NinebotDecodeResult.Ok).frame)
    }

    // --- Error handling ----------------------------------------------

    @Test fun `decoder rejects bad prefix`() {
        val wire = hex("AA 55 03 09 01 10 0E D4 FF")
        val res = NinebotDecoder.decode(wire, NinebotFamily.N1)
        assertEquals(NinebotDecodeError.BadPrefix, (res as NinebotDecodeResult.Fail).error)
    }

    @Test fun `decoder flags short buffer`() {
        val wire = hex("55 AA 03")
        assertTrue(NinebotDecoder.decode(wire, NinebotFamily.N1) is NinebotDecodeResult.Fail)
    }

    @Test fun `decoder rejects corrupted checksum`() {
        // Flip the CHK_LO byte.
        val wire = hex("55 AA 03 09 01 10 0E 00 FF")
        val res = NinebotDecoder.decode(wire, NinebotFamily.N1)
        assertTrue(res is NinebotDecodeResult.Fail)
        assertTrue((res as NinebotDecodeResult.Fail).error is NinebotDecodeError.BadChecksum)
    }

    @Test fun `decoder rejects wrong-sized keystream`() {
        val res = NinebotDecoder.decode(hex("55 AA"), NinebotFamily.N1, gamma = ByteArray(5))
        assertTrue(res is NinebotDecodeResult.Fail)
        assertTrue((res as NinebotDecodeResult.Fail).error is NinebotDecodeError.BadKeystream)
    }

    // --- Command-builder helpers -------------------------------------

    @Test fun `getKey produces an N2 handshake to the KeyGenerator endpoint`() {
        val wire = NinebotCommandBuilder.getKey()
        // 帠 defaults to zero, so wire is plaintext: prefix + LEN=02
        // + SRC(09) + DST(16) + CMD(5B) + PARAM(5B) + CHK.
        val res = NinebotDecoder.decode(wire, NinebotFamily.N2)
        assertTrue(res is NinebotDecodeResult.Ok)
        val f = (res as NinebotDecodeResult.Ok).frame
        assertEquals(NinebotCommandBuilder.ADDR_HOST_APP_DEFAULT, f.src)
        assertEquals(NinebotCommandBuilder.ADDR_KEY_GENERATOR, f.dst)
        assertEquals(NinebotCommandBuilder.CMD_GET_KEY, f.cmd)
        assertEquals(NinebotCommandBuilder.PARAM_GET_KEY, f.param)
        assertEquals(0, f.data.size)
    }

    @Test fun `readParam defaults to CMD 0x01 and single-byte DATA`() {
        val wire = NinebotCommandBuilder.readParam(
            param = NinebotCommandBuilder.PARAM_BATTERY_LEVEL,
            readLength = 2,
        )
        val f = (NinebotDecoder.decode(wire, NinebotFamily.N2) as NinebotDecodeResult.Ok).frame
        assertEquals(NinebotCommandBuilder.CMD_READ, f.cmd)
        assertEquals(NinebotCommandBuilder.PARAM_BATTERY_LEVEL, f.param)
        assertArrayEquals(byteArrayOf(0x02), f.data)
    }

    @Test fun `writeParam sets CMD 0x03 and carries payload`() {
        val payload = byteArrayOf(0x11, 0x22, 0x33)
        val wire = NinebotCommandBuilder.writeParam(param = 0x40, payload = payload)
        val f = (NinebotDecoder.decode(wire, NinebotFamily.N2) as NinebotDecodeResult.Ok).frame
        assertEquals(NinebotCommandBuilder.CMD_WRITE, f.cmd)
        assertEquals(0x40, f.param)
        assertArrayEquals(payload, f.data)
    }

    @Test fun `encoder then decoder under session key round-trips GetKey response`() {
        // Simulate: host sends GetKey with 帠=0, device reply contains
        // a 16-byte key, host adopts that key for the NEXT request.
        val newGamma = ByteArray(16) { (0xA0 + it).toByte() }

        val helloWire = NinebotCommandBuilder.readParam(
            param = NinebotCommandBuilder.PARAM_FIRMWARE_VERSION,
            readLength = 2,
            gamma = newGamma,
        )
        val decoded = NinebotDecoder.decode(helloWire, NinebotFamily.N2, newGamma)
        assertTrue(decoded is NinebotDecodeResult.Ok)
        val f = (decoded as NinebotDecodeResult.Ok).frame
        assertEquals(NinebotCommandBuilder.PARAM_FIRMWARE_VERSION, f.param)
    }

    // --- 禮3.4.1 telemetry page ---------------------------------------

    @Test fun `B0 page parses documented offsets`() {
        // Hand-assembled DATA: battery=0x0064=100% at 8, speed(std)=
        // 0x03E8=1000 at 10, totalDist=0x00012345 at 14,
        // temp=0x012C=300 (30.0 C) at 22, voltage=0x0FA0=4000 at 24,
        // current=0xFFC4= -60 at 26, speedS2=0x07D0=2000 at 28.
        val data = ByteArray(30)
        // offset 8..9: 64 00
        data[8] = 0x64
        // offset 10..11: E8 03
        data[10] = 0xE8.toByte(); data[11] = 0x03
        // offset 14..17: 45 23 01 00
        data[14] = 0x45; data[15] = 0x23; data[16] = 0x01; data[17] = 0x00
        // offset 22..23: 2C 01
        data[22] = 0x2C; data[23] = 0x01
        // offset 24..25: A0 0F
        data[24] = 0xA0.toByte(); data[25] = 0x0F
        // offset 26..27: C4 FF (signed = -60)
        data[26] = 0xC4.toByte(); data[27] = 0xFF.toByte()
        // offset 28..29: D0 07
        data[28] = 0xD0.toByte(); data[29] = 0x07

        val tel = NinebotTelemetryB0.parse(data)
        assertEquals(100, tel.batteryPercent)
        assertEquals(1000, tel.speedStandardRaw)
        assertEquals(0x12345L, tel.totalDistanceMetres)
        assertEquals(300, tel.temperatureTenthsC)
        assertEquals(30.0, tel.temperatureCelsius, 1e-9)
        assertEquals(4000, tel.voltageHundredthsV)
        assertEquals(40.0, tel.voltageVolts, 1e-9)
        assertEquals(-60, tel.currentHundredthsA)
        assertEquals(2000, tel.speedS2Raw)
        assertEquals(20.0, tel.speedS2Kmh, 1e-9)
    }

    // --- 禮3.4.2 activation date --------------------------------------

    @Test fun `activation date decodes per 禮3-4-2 formula`() {
        // (year 2026, month 4, day 19) ??D = (26<<9) | (4<<5) | 19
        val d = (26 shl 9) or (4 shl 5) or 19
        val ymd = NinebotActivationDate.decode(d)
        assertEquals(2026, ymd.year)
        assertEquals(4, ymd.month)
        assertEquals(19, ymd.day)
    }
}


