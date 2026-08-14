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

    @Test fun `vector section 4 decodes without CRC`() {
        // 42-byte Sherman frame (L = 0x25 = 37). The final six bytes
        // at offsets 36..41 are reserved and zeroed per §3.3.
        val frame = hex(
            """
            DC 5A 5C 20 25 CD 00 00  07 1F 00 00 C7 78 00 28
            00 00 11 0B 0E 10 00 01  0A F0 0A F0 04 22 00 03
            00 14 00 00 00 00 00 00  00 00
            """,
        )

        val result = VeteranDecoder.decode(frame)
        assertTrue(result is VeteranDecoder.DecodeResult.Ok)
        result as VeteranDecoder.DecodeResult.Ok
        assertEquals(42, result.consumedBytes)

        val decoded = result.frame
        assertFalse(decoded.crc32Present)
        assertEquals(37, decoded.payloadLength)

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

    @Test fun `frame with CRC is accepted when CRC matches`() {
        // Build a synthetic minimum-size frame (L = 32, payload all
        // zeros except firmware word so the decoded fields are well
        // defined) and append the matching CRC32 trailer. The
        // `expectCrcAlways=true` path exercises the "once negotiated"
        // branch even though L < 38.
        val payload = ByteArray(32)
        // firmware version at frame offset 28 (= payload offset 23)
        // set to 4321 -> "004.3.21".
        payload[23] = 0x10; payload[24] = 0xE1.toByte()

        val withoutCrc = ByteArray(5 + payload.size).also {
            it[0] = 0xDC.toByte()
            it[1] = 0x5A.toByte()
            it[2] = 0x5C.toByte()
            it[3] = 0x20.toByte()
            it[4] = payload.size.toByte()
            System.arraycopy(payload, 0, it, 5, payload.size)
        }
        val expectedCrc = VeteranDecoder.computeCrc32(withoutCrc, 0, withoutCrc.size)
        val withCrc = ByteArray(withoutCrc.size + 4).also {
            System.arraycopy(withoutCrc, 0, it, 0, withoutCrc.size)
            it[withoutCrc.size]     = (expectedCrc ushr 24).toByte()
            it[withoutCrc.size + 1] = (expectedCrc ushr 16).toByte()
            it[withoutCrc.size + 2] = (expectedCrc ushr 8).toByte()
            it[withoutCrc.size + 3] = expectedCrc.toByte()
        }

        val result = VeteranDecoder.decode(withCrc, expectCrcAlways = true)
        assertTrue(result is VeteranDecoder.DecodeResult.Ok)
        result as VeteranDecoder.DecodeResult.Ok
        assertEquals(withCrc.size, result.consumedBytes)
        assertTrue(result.frame.crc32Present)
        assertEquals(4321, result.frame.firmwareVersionRaw)
        assertEquals("004.3.21", result.frame.firmwareVersionString)
    }

    @Test fun `frame with bad CRC is rejected`() {
        val payload = ByteArray(32)
        val withoutCrc = ByteArray(5 + payload.size).also {
            it[0] = 0xDC.toByte()
            it[1] = 0x5A.toByte()
            it[2] = 0x5C.toByte()
            it[3] = 0x20.toByte()
            it[4] = payload.size.toByte()
        }
        val withCrc = ByteArray(withoutCrc.size + 4).also {
            System.arraycopy(withoutCrc, 0, it, 0, withoutCrc.size)
            // Deliberately incorrect CRC.
            it[withoutCrc.size]     = 0x00
            it[withoutCrc.size + 1] = 0x00
            it[withoutCrc.size + 2] = 0x00
            it[withoutCrc.size + 3] = 0x00
        }

        val result = VeteranDecoder.decode(withCrc, expectCrcAlways = true)
        assertTrue(result is VeteranDecoder.DecodeResult.Fail)
        result as VeteranDecoder.DecodeResult.Fail
        assertTrue(result.error is VeteranDecoder.DecodeError.BadCrc)
    }

    @Test fun `frame with L greater than 38 requires CRC`() {
        // L = 40 triggers the `L > 38` branch in §2.3 / §6.2 so that a
        // CRC trailer is mandatory.
        val payload = ByteArray(40)
        val buf = ByteArray(5 + payload.size).also {
            it[0] = 0xDC.toByte()
            it[1] = 0x5A.toByte()
            it[2] = 0x5C.toByte()
            it[3] = 0x20.toByte()
            it[4] = payload.size.toByte()
        }
        val result = VeteranDecoder.decode(buf)
        // CRC expected but not present -> TooShort.
        assertTrue(result is VeteranDecoder.DecodeResult.Fail)
        result as VeteranDecoder.DecodeResult.Fail
        assertEquals(VeteranDecoder.DecodeError.TooShort, result.error)
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
                payloadLength = 32,
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
