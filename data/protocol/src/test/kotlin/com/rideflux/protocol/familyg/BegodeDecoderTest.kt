/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyg

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins decoder behaviour to the hex vectors in
 * `clean-room/spec/TEST_VECTORS.md` §1 and §2. Any deviation in the
 * decoder must be accompanied by a spec revision.
 */
class BegodeDecoderTest {

    @Test fun `live telemetry type 0x00 matches TEST_VECTORS section 1`() {
        val frame = hex(
            """
            55 AA 19 F0 00 00 00 00  00 00 01 2C FD CA 00 01
            FF F8 00 18 5A 5A 5A 5A
            """,
        )

        val decoded = BegodeDecoder.decode(frame)

        assertTrue(decoded is BegodeFrame.LiveTelemetry)
        decoded as BegodeFrame.LiveTelemetry

        assertEquals(6640, decoded.voltageHundredthsV)
        assertEquals(66.40, decoded.voltageVolts, 1e-9)

        assertEquals(0, decoded.speedHundredthsMs)
        assertEquals(0.0, decoded.speedKmh, 1e-9)

        assertEquals(0L, decoded.tripMeters)

        assertEquals(300, decoded.phaseCurrentHundredthsA)
        assertEquals(3.00, decoded.phaseCurrentAmps, 1e-9)

        assertEquals(-566, decoded.imuTempRaw)
        assertEquals(34.87, BegodeTemperature.celsiusMpu6050(-566), 0.01)

        assertEquals(1, decoded.pwmTenthsPercent)
        assertEquals(0.1, decoded.pwmPercent, 1e-9)

        assertEquals(0x18, decoded.subIndex)
    }

    @Test fun `linear battery curve clamps at 100`() {
        // 66.40 V -> (6640 - 5290) / 13 = 103 -> clamp 100, per TEST_VECTORS §1.
        assertEquals(100, BegodeBatteryCurve.linearPercent(6640))
    }

    @Test fun `settings frame type 0x04 matches TEST_VECTORS section 2`() {
        val frame = hex(
            """
            55 AA 00 0A 4A 12 48 00  1C 20 00 2A 00 03 00 07
            00 08 04 18 5A 5A 5A 5A
            """,
        )

        val decoded = BegodeDecoder.decode(frame)

        assertTrue(decoded is BegodeFrame.SettingsAndOdometer)
        decoded as BegodeFrame.SettingsAndOdometer

        assertEquals(674_322L, decoded.totalDistanceMeters)
        assertEquals(0x4800, decoded.settingsBitfield)
        assertEquals(7200, decoded.autoPowerOffSeconds)
        assertEquals(42, decoded.tiltbackSpeedKmh)
        assertEquals(0, decoded.ledMode)
        assertEquals(0x07, decoded.alertBitmap)
        assertEquals(0, decoded.lightMode)   // raw 0x08 masked with 0x03

        // Alert bits per §4.1: 0x07 sets bits 0, 1, 2.
        assertTrue(decoded.alertWheelAlarm)
        assertTrue(decoded.alertSpeedLevel2)
        assertTrue(decoded.alertSpeedLevel1)
        assertFalse(decoded.alertLowVoltage)
        assertFalse(decoded.alertOverVoltage)
        assertFalse(decoded.alertOverTemperature)

        // Miles-mode flag (bit 0 of settings bitfield) is clear.
        assertFalse(decoded.milesMode)

        assertEquals(0x18, decoded.subIndex)
    }

    @Test fun `decoder rejects wrong header`() {
        val frame = ByteArray(BegodeDecoder.FRAME_SIZE).also {
            it[0] = 0x55; it[1] = 0x55
            for (i in 20..23) it[i] = 0x5A
        }
        assertNull(BegodeDecoder.decode(frame))
    }

    @Test fun `decoder rejects wrong footer`() {
        val frame = ByteArray(BegodeDecoder.FRAME_SIZE).also {
            it[0] = 0x55; it[1] = 0xAA.toByte()
            for (i in 20..23) it[i] = 0x5A
            it[22] = 0x00 // corrupt one footer byte
        }
        assertNull(BegodeDecoder.decode(frame))
    }

    @Test fun `decoder preserves unknown type payloads`() {
        val frame = ByteArray(BegodeDecoder.FRAME_SIZE).also {
            it[0] = 0x55; it[1] = 0xAA.toByte()
            for (i in 20..23) it[i] = 0x5A
            it[18] = 0x77.toByte() // unknown type
            it[19] = 0x01.toByte() // sub-index
            it[5] = 0xAB.toByte()
        }
        val decoded = BegodeDecoder.decode(frame)
        assertNotNull(decoded)
        assertTrue(decoded is BegodeFrame.Unknown)
        decoded as BegodeFrame.Unknown
        assertEquals(0x77, decoded.typeCode)
        assertEquals(1, decoded.subIndex)
        assertEquals(0xAB.toByte(), decoded.payload[3]) // offset 5 - 2
    }
}
