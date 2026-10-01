/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyv

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins decoder behaviour to `clean-room/spec/TEST_VECTORS.md` §4 and
 * §7, and to the field map in `PROTOCOL_SPEC.md` §3.3 / §6.2 / §8.3 /
 * §8.4.
 */
class VeteranDecoderTest {

    @Test fun `a real 36-byte frame decodes without CRC`() {
        // Complete frame as a wheel sends it: length byte 0x20, so 4 + 32 = 36 bytes. (An earlier
        // version of this vector had six zero bytes appended to fit a length read from offset 4.)
        // These bytes are one of the frames in WheelLog's VeteranAdapterTest (see NOTICE).
        val frame = hex(
            """
            DC 5A 5C 20 25 CD 00 00  07 1F 00 00 C7 78 00 28
            00 00 11 0B 0E 10 00 01  0A F0 0A F0 04 22 00 03
            00 14 00 00
            """,
        )

        val result = VeteranDecoder.decode(frame)
        assertTrue(result is VeteranDecoder.DecodeResult.Ok)
        result as VeteranDecoder.DecodeResult.Ok
        assertEquals(36, result.consumedBytes)

        val decoded = result.frame
        assertFalse(decoded.crc32Present)
        assertEquals(32, decoded.declaredLength)

        assertEquals(9677, decoded.voltageHundredthsV)
        assertEquals(96.77, decoded.voltageVolts, 1e-9)

        assertEquals(0, decoded.speedTenthsKmh)
        assertEquals(0.0, decoded.speedKmh, 1e-9)

        // §8.3 word-swap: wire bytes `07 1F 00 00` -> 1823.
        assertEquals(1823L, decoded.tripMeters)

        // `C7 78 00 28` word-swapped -> (0x00 << 24) | (0x28 << 16)
        //                              | (0xC7 << 8) | 0x78 = 0x0028C778 = 2672504.
        assertEquals(2_672_504L, decoded.totalMeters)

        assertEquals(0, decoded.phaseCurrentHundredthsA)
        assertEquals(4363, decoded.temperatureHundredthsC)
        assertEquals(43.63, decoded.temperatureCelsius, 1e-9)

        assertEquals(3600, decoded.autoPowerOffSeconds)
        assertEquals(VeteranFrame.ChargeStatus.CHARGING, decoded.chargeStatus)

        assertEquals(2800, decoded.speedAlertTenthsKmh)
        assertEquals(2800, decoded.speedTiltbackTenthsKmh)

        // §8.4 firmware encoding: 1058 -> "001.0.58".
        assertEquals(1058, decoded.firmwareVersionRaw)
        assertEquals("001.0.58", decoded.firmwareVersionString)

        assertEquals(3, decoded.pedalsMode)
        assertEquals(20, decoded.pitchAngleHundredthsDeg)
        assertEquals(0.20, decoded.pitchAngleDegrees, 1e-9)
        assertEquals(0, decoded.hardwarePwmHundredthsPercent)
    }

    @Test fun `vector section 7 matches CRC32 ISO-HDLC`() {
        // Pinned CRC-32 of the 15-byte buffer in TEST_VECTORS §7.
        val inputWithoutCrc = hex("DC 5A 5C 20 0A 01 02 03 04 05 06 07 08 09 0A")
        val crc = VeteranDecoder.computeCrc32(inputWithoutCrc, 0, inputWithoutCrc.size)
        assertEquals(
            "CRC-32/ISO-HDLC of the TEST_VECTORS §7 bytes (printed as hex: %08X)"
                .format(crc),
            0x08009D77L,
            crc,
        )
    }

    /**
     * A synthetic frame of `length + 4` bytes whose last four bytes are the CRC-32 of the first
     * `length` bytes (the geometry of the community implementation; no real capture backs it).
     * The firmware word at offset 28 is set to 4321 -> "004.3.21" so the decoded fields are
     * well defined. For `length` = 32 the CRC overlaps the pitch/PWM fields, which no test reads.
     */
    private fun crcFrame(length: Int, crc: Long? = null): ByteArray {
        val frame = ByteArray(length + 4)
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A.toByte()
        frame[2] = 0x5C.toByte()
        frame[3] = length.toByte()
        frame[28] = 0x10
        frame[29] = 0xE1.toByte()
        val value = crc ?: VeteranDecoder.computeCrc32(frame, 0, length)
        frame[length] = (value ushr 24).toByte()
        frame[length + 1] = (value ushr 16).toByte()
        frame[length + 2] = (value ushr 8).toByte()
        frame[length + 3] = value.toByte()
        return frame
    }

    @Test fun `a long frame carries a CRC that is checked and consumed`() {
        val withCrc = crcFrame(length = 40)

        val result = VeteranDecoder.decode(withCrc)

        assertTrue(result is VeteranDecoder.DecodeResult.Ok)
        result as VeteranDecoder.DecodeResult.Ok
        assertEquals(44, result.consumedBytes)
        assertTrue(result.frame.crc32Present)
        assertEquals(40, result.frame.declaredLength)
        assertEquals(4321, result.frame.firmwareVersionRaw)
        assertEquals("004.3.21", result.frame.firmwareVersionString)
    }

    @Test fun `a short frame is checked for a CRC once the session has shown one`() {
        val withCrc = crcFrame(length = 32)

        val strict = VeteranDecoder.decode(withCrc, expectCrcAlways = true)
        assertTrue(strict is VeteranDecoder.DecodeResult.Ok)
        assertTrue((strict as VeteranDecoder.DecodeResult.Ok).frame.crc32Present)
        assertEquals(36, strict.consumedBytes)

        // Without the latch the same bytes are an ordinary CRC-less frame.
        val lenient = VeteranDecoder.decode(withCrc)
        assertTrue(lenient is VeteranDecoder.DecodeResult.Ok)
        assertFalse((lenient as VeteranDecoder.DecodeResult.Ok).frame.crc32Present)
    }

    @Test fun `frame with bad CRC is rejected`() {
        val result = VeteranDecoder.decode(crcFrame(length = 40, crc = 0L))

        assertTrue(result is VeteranDecoder.DecodeResult.Fail)
        result as VeteranDecoder.DecodeResult.Fail
        assertTrue(result.error is VeteranDecoder.DecodeError.BadCrc)
    }

    @Test fun `a frame that is one byte short waits for the rest`() {
        val complete = crcFrame(length = 40)

        val result = VeteranDecoder.decode(complete.copyOf(complete.size - 1))

        assertTrue(result is VeteranDecoder.DecodeResult.Fail)
        result as VeteranDecoder.DecodeResult.Fail
        assertEquals(VeteranDecoder.DecodeError.TooShort, result.error)
    }

    @Test fun `a length that cannot hold the fields is a false magic, not a frame to wait for`() {
        val bogus = ByteArray(40).also {
            it[0] = 0xDC.toByte()
            it[1] = 0x5A.toByte()
            it[2] = 0x5C.toByte()
            it[3] = 0x10
        }

        val result = VeteranDecoder.decode(bogus)

        assertTrue(result is VeteranDecoder.DecodeResult.Fail)
        result as VeteranDecoder.DecodeResult.Fail
        assertEquals(VeteranDecoder.DecodeError.LengthMismatch, result.error)
    }

    @Test fun `bad magic is rejected`() {
        val bogus = hex("DE AD BE EF 25 00 00 00 00 00")
        val result = VeteranDecoder.decode(bogus)
        assertTrue(result is VeteranDecoder.DecodeResult.Fail)
        result as VeteranDecoder.DecodeResult.Fail
        assertEquals(VeteranDecoder.DecodeError.BadMagic, result.error)
    }

    @Test fun `word swap handles large upper words`() {
        // Wire bytes `12 34 AB CD` -> lower word 0x1234, upper 0xABCD
        // -> combined 0xABCD1234.
        val buf = hex("12 34 AB CD")
        assertEquals(0xABCD1234L, VeteranDecoder.wordSwapU32(buf, 0))
    }

    @Test fun `firmware version encoding edge cases`() {
        // 0 -> "000.0.00" ; 9999 -> "009.9.99" ; 58 -> "000.0.58" ;
        // 1000 -> "001.0.00". Validated through an isolated frame.
        val cases = listOf(
            0 to "000.0.00",
            58 to "000.0.58",
            1000 to "001.0.00",
            9999 to "009.9.99",
            5043 to "005.0.43",
        )
        for ((raw, expected) in cases) {
            val frame = VeteranFrame(
                declaredLength = 32,
                voltageHundredthsV = 0,
                speedTenthsKmh = 0,
                tripMeters = 0,
                totalMeters = 0,
                phaseCurrentHundredthsA = 0,
                temperatureHundredthsC = 0,
                autoPowerOffSeconds = 0,
                chargeMode = 0,
                speedAlertTenthsKmh = 0,
                speedTiltbackTenthsKmh = 0,
                firmwareVersionRaw = raw,
                pedalsMode = 0,
                pitchAngleHundredthsDeg = 0,
                hardwarePwmHundredthsPercent = 0,
                crc32Present = false,
            )
            assertEquals("raw=$raw", expected, frame.firmwareVersionString)
        }
    }
}
