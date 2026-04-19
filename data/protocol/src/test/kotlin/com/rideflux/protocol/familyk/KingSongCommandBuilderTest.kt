/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins Family K command-builder output to the 20-byte host-to-device
 * frame layout defined in `PROTOCOL_SPEC.md` §2.2 and §3.2.
 *
 * Common invariants that every builder output must satisfy:
 *  * length is exactly 20 bytes;
 *  * bytes 0..1 are `AA 55`;
 *  * bytes 18..19 are `5A 5A`;
 *  * byte 16 equals the documented command code;
 *  * byte 17 defaults to `0x14` unless the command overrides it.
 *
 * Tests also round-trip every builder output through the decoder
 * [KingSongDecoder] to prove wire-format consistency between the
 * encoder and decoder sides.
 */
class KingSongCommandBuilderTest {

    private fun assertCommonFrame(frame: ByteArray, commandCode: Int, trailerMagic: Byte = 0x14) {
        assertEquals("frame length", 20, frame.size)
        assertEquals("header[0]", 0xAA.toByte(), frame[0])
        assertEquals("header[1]", 0x55.toByte(), frame[1])
        assertEquals("command code", commandCode.toByte(), frame[16])
        assertEquals("trailer magic", trailerMagic, frame[17])
        assertEquals("tail[18]", 0x5A.toByte(), frame[18])
        assertEquals("tail[19]", 0x5A.toByte(), frame[19])
    }

    private fun assertPayloadZeros(frame: ByteArray, except: Set<Int> = emptySet()) {
        for (i in 2..15) {
            if (i !in except) {
                assertEquals("payload[$i] must be zero", 0.toByte(), frame[i])
            }
        }
    }

    @Test fun `request serial number frame`() {
        val frame = KingSongCommandBuilder.requestSerialNumber()
        assertCommonFrame(frame, 0x63)
        assertPayloadZeros(frame)
    }

    @Test fun `request device name frame`() {
        val frame = KingSongCommandBuilder.requestDeviceName()
        assertCommonFrame(frame, 0x9B)
        assertPayloadZeros(frame)
    }

    @Test fun `request alarm settings frame`() {
        val frame = KingSongCommandBuilder.requestAlarmSettings()
        assertCommonFrame(frame, 0x98)
        assertPayloadZeros(frame)
    }

    @Test fun `beep frame`() {
        val frame = KingSongCommandBuilder.beep()
        assertCommonFrame(frame, 0x88)
        assertPayloadZeros(frame)
    }

    @Test fun `wheel calibration frame`() {
        val frame = KingSongCommandBuilder.wheelCalibration()
        assertCommonFrame(frame, 0x89)
        assertPayloadZeros(frame)
    }

    @Test fun `power off frame`() {
        val frame = KingSongCommandBuilder.powerOff()
        assertCommonFrame(frame, 0x40)
        assertPayloadZeros(frame)
    }

    @Test fun `set pedals mode frame has spec-mandated constants`() {
        val frame = KingSongCommandBuilder.setPedalsMode(modeIndex = 0x02)
        // Per §3.2: command 0x87, trailer magic at byte 17 is 0x15,
        // payload byte at frame offset 3 must be 0xE0.
        assertCommonFrame(frame, 0x87, trailerMagic = 0x15)
        assertEquals("mode index at frame offset 2", 0x02.toByte(), frame[2])
        assertEquals("0xE0 magic at frame offset 3", 0xE0.toByte(), frame[3])
        assertPayloadZeros(frame, except = setOf(2, 3))
    }

    @Test fun `generic command rejects wrong payload size`() {
        assertThrows(IllegalArgumentException::class.java) {
            KingSongCommandBuilder.command(0x63, payload = ByteArray(10))
        }
    }

    @Test fun `generic command accepts raw payload bytes exactly`() {
        val payload = ByteArray(14) { (it + 1).toByte() } // 0x01..0x0E
        val frame = KingSongCommandBuilder.command(0xA9, payload = payload, trailerMagic = 0x77)
        assertCommonFrame(frame, 0xA9, trailerMagic = 0x77)
        for (i in 0 until 14) {
            assertEquals("payload[$i]", payload[i], frame[2 + i])
        }
    }

    @Test fun `encoder-decoder round trip for every named helper`() {
        // The KingSong decoder treats known-command codes as
        // telemetry variants, so commands whose code is not a known
        // telemetry code (0xA9, 0xB9) should decode to Unknown with
        // the correct command code and payload.
        val cases = listOf(
            0x63 to KingSongCommandBuilder.requestSerialNumber(),
            0x9B to KingSongCommandBuilder.requestDeviceName(),
            0x98 to KingSongCommandBuilder.requestAlarmSettings(),
            0x88 to KingSongCommandBuilder.beep(),
            0x89 to KingSongCommandBuilder.wheelCalibration(),
            0x40 to KingSongCommandBuilder.powerOff(),
        )
        for ((code, frame) in cases) {
            val decoded = KingSongDecoder.decode(frame)
            assertEquals(
                "command $code should round-trip through decoder",
                true,
                decoded is KingSongFrame.Unknown,
            )
            decoded as KingSongFrame.Unknown
            assertEquals("command code for 0x${"%02X".format(code)}", code, decoded.commandCode)
            // Sub-index / trailer-magic position — decoder reads byte 17 as subIndex.
            assertEquals("default trailer 0x14 is read as sub-index 0x14", 0x14, decoded.subIndex)
        }
    }

    // ---- §10.3.1 pedals-mode enum overload -----------------------------

    @Test fun `set pedals mode enum overload uses HARD=0 MEDIUM=1 SOFT=2`() {
        val hard = KingSongCommandBuilder.setPedalsMode(KingSongCommandBuilder.PedalsMode.HARD)
        val medium = KingSongCommandBuilder.setPedalsMode(KingSongCommandBuilder.PedalsMode.MEDIUM)
        val soft = KingSongCommandBuilder.setPedalsMode(KingSongCommandBuilder.PedalsMode.SOFT)
        assertEquals(0x00.toByte(), hard[2])
        assertEquals(0x01.toByte(), medium[2])
        assertEquals(0x02.toByte(), soft[2])
        // Trailer and magic invariants preserved across every mode.
        for (f in listOf(hard, medium, soft)) {
            assertCommonFrame(f, 0x87, trailerMagic = 0x15)
            assertEquals(0xE0.toByte(), f[3])
            assertPayloadZeros(f, except = setOf(2, 3))
        }
    }

    // ---- §10.3.2 alarm / max-speed ------------------------------------

    @Test fun `set alarm and max speed places values at 2 4 6 8 with zero padding at 3 5 7`() {
        val frame = KingSongCommandBuilder.setAlarmAndMaxSpeed(
            alarm1Kmh = 10, alarm2Kmh = 20, alarm3Kmh = 30, maxSpeedKmh = 40,
        )
        assertCommonFrame(frame, 0x85)
        assertEquals("alarm1 @ byte 2", 10.toByte(), frame[2])
        assertEquals("padding @ byte 3", 0.toByte(), frame[3])
        assertEquals("alarm2 @ byte 4", 20.toByte(), frame[4])
        assertEquals("padding @ byte 5", 0.toByte(), frame[5])
        assertEquals("alarm3 @ byte 6", 30.toByte(), frame[6])
        assertEquals("padding @ byte 7", 0.toByte(), frame[7])
        assertEquals("maxSpeed @ byte 8", 40.toByte(), frame[8])
        assertPayloadZeros(frame, except = setOf(2, 4, 6, 8))
    }

    @Test fun `set alarm and max speed encodes unsigned 8-bit values above 127`() {
        val frame = KingSongCommandBuilder.setAlarmAndMaxSpeed(200, 210, 220, 230)
        assertEquals(200.toByte(), frame[2])
        assertEquals(210.toByte(), frame[4])
        assertEquals(220.toByte(), frame[6])
        assertEquals(230.toByte(), frame[8])
    }

    @Test fun `set alarm and max speed rewrites to 0x98 when all four values are zero`() {
        // §10.3.2 degenerate case: the all-zero write frame must be
        // rewritten as a 0x98 query so the device reports its current
        // settings instead of silently zeroing them.
        val frame = KingSongCommandBuilder.setAlarmAndMaxSpeed(0, 0, 0, 0)
        assertCommonFrame(frame, 0x98)
        assertPayloadZeros(frame)
    }

    @Test fun `set alarm and max speed rejects out of range values`() {
        assertThrows(IllegalArgumentException::class.java) {
            KingSongCommandBuilder.setAlarmAndMaxSpeed(-1, 0, 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KingSongCommandBuilder.setAlarmAndMaxSpeed(0, 0, 0, 256)
        }
    }

    // ---- §10.3.3 headlight --------------------------------------------

    @Test fun `set light mode applies 0x12 bias on byte 2 and 0x01 magic on byte 3`() {
        val off = KingSongCommandBuilder.setLightMode(0)
        val on = KingSongCommandBuilder.setLightMode(1)
        val aux = KingSongCommandBuilder.setLightMode(2)
        assertEquals("off byte 2 = 0x12", 0x12.toByte(), off[2])
        assertEquals("on byte 2 = 0x13", 0x13.toByte(), on[2])
        assertEquals("aux byte 2 = 0x14", 0x14.toByte(), aux[2])
        for (f in listOf(off, on, aux)) {
            assertCommonFrame(f, 0x73)
            assertEquals("0x01 magic @ byte 3", 0x01.toByte(), f[3])
            assertPayloadZeros(f, except = setOf(2, 3))
        }
    }

    @Test fun `set light mode enum overload maps OFF ON AUX to 0 1 2`() {
        assertEquals(0x12.toByte(), KingSongCommandBuilder.setLightMode(KingSongCommandBuilder.LightMode.OFF)[2])
        assertEquals(0x13.toByte(), KingSongCommandBuilder.setLightMode(KingSongCommandBuilder.LightMode.ON)[2])
        assertEquals(0x14.toByte(), KingSongCommandBuilder.setLightMode(KingSongCommandBuilder.LightMode.AUX)[2])
    }

    // ---- §10.3.4 LED mode ---------------------------------------------

    @Test fun `set LED mode places raw mode on byte 2 with no bias`() {
        val frame = KingSongCommandBuilder.setLedMode(0x05)
        assertCommonFrame(frame, 0x6C)
        assertEquals(0x05.toByte(), frame[2])
        assertPayloadZeros(frame, except = setOf(2))
    }

    // ---- §10.3.5 strobe mode ------------------------------------------

    @Test fun `set strobe mode places raw mode on byte 2 with no bias`() {
        val frame = KingSongCommandBuilder.setStrobeMode(0x07)
        assertCommonFrame(frame, 0x53)
        assertEquals(0x07.toByte(), frame[2])
        assertPayloadZeros(frame, except = setOf(2))
    }
}
