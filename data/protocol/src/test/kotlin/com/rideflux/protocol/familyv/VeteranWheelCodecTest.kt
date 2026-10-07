/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyv

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.telemetry.ChargingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeteranWheelCodecTest {

    @Test
    fun `incomplete input cannot grow the frame buffer without bound`() {
        val codec = VeteranWheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState() as VeteranWheelCodec.VeteranState
        val incomplete = ByteArray(VeteranWheelCodec.MAX_FRAME_BUFFER_SIZE * 3) { 0xDC.toByte() }

        val events = codec.decode(state, incomplete)

        assertTrue(state.buffer.size <= VeteranWheelCodec.MAX_FRAME_BUFFER_SIZE)
        assertTrue(events.any { it is DecodeEvent.Malformed && "overflow" in it.reason })
    }

    private fun makeNosfetFrame(
        voltageHundredthsV: Int,
        b30: Int,
        b28: Int,
        b29: Int,
        speedTenthsKmh: Int = 250,
        chargeMode: Int = 1,
    ): ByteArray {
        val frame = ByteArray(36)
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A.toByte()
        frame[2] = 0x5C.toByte()
        frame[3] = 0x20.toByte() // length = 32
        frame[4] = (voltageHundredthsV ushr 8).toByte()
        frame[5] = voltageHundredthsV.toByte()
        frame[6] = (speedTenthsKmh ushr 8).toByte()
        frame[7] = speedTenthsKmh.toByte()
        // trip meters = 1000m (word swapped: 0x03, 0xE8, 0x00, 0x00)
        frame[8] = 0x03
        frame[9] = 0xE8.toByte()
        frame[10] = 0x00
        frame[11] = 0x00
        // total meters = 50000m (0x0000C350 -> swap: 0xC3, 0x50, 0x00, 0x00)
        frame[12] = 0xC3.toByte()
        frame[13] = 0x50.toByte()
        frame[14] = 0x00
        frame[15] = 0x00
        // phase current = 150 (15.0A in Modern)
        frame[16] = 0x00
        frame[17] = 0x96.toByte()
        // temperature = 3500 (35.0°C)
        frame[18] = 0x0D
        frame[19] = 0xAC.toByte()
        frame[20] = 0x02
        frame[21] = 0x58.toByte()
        frame[22] = 0x00
        frame[23] = chargeMode.toByte()
        frame[24] = 0x01
        frame[25] = 0xC2.toByte()
        frame[26] = 0x01
        frame[27] = 0xF4.toByte()
        // version bytes [30, 28, 29]
        frame[28] = b28.toByte()
        frame[29] = b29.toByte()
        frame[30] = b30.toByte()
        frame[31] = 0x02 // ride mode
        frame[32] = 0x00
        frame[33] = 0x00 // pitch
        frame[34] = 0x1F
        frame[35] = 0x40 // outputRaw
        return frame
    }

    @Test
    fun `auto latches to MODERN_NOSFET for Apex and computes exact 100 percent SOC`() {
        // Apex: 501000 = 0x07A508 -> b30=0x07, b28=0xA5, b29=0x08
        // Pack voltage 148.50V (14850 centivolts) = 100% on Apex
        val apexFrame = makeNosfetFrame(
            voltageHundredthsV = 14850,
            b30 = 0x07,
            b28 = 0xA5,
            b29 = 0x08,
        )

        val codec = VeteranWheelCodec("11:22:33:44:55:66", profile = VeteranProtocolProfile.LEGACY)
        val state = codec.newState()
        val events = codec.decode(state, apexFrame)

        val identified = events.filterIsInstance<DecodeEvent.Identified>().firstOrNull()
        val telem = events.filterIsInstance<DecodeEvent.TelemetryUpdate>().firstOrNull()

        assertTrue(identified != null)
        assertEquals("Nosfet APEX", identified!!.identity.modelName)
        assertEquals("501.0.00", identified.identity.firmwareVersion)

        assertTrue(telem != null)
        assertEquals(148.50f, telem!!.snapshot.voltageV)
        assertEquals(100f, telem.snapshot.batteryPercent)
        // In modern profile, offset 34 is vendor output multiplier, so pwmPercent must be null
        assertNull(telem.snapshot.pwmPercent)
        assertEquals(ChargingState.CHARGING, telem.snapshot.chargingState)
    }

    @Test
    fun `auto latches to MODERN_NOSFET for Aero and computes exact SOC`() {
        // Aero: 502012 = 0x07A8FC -> b30=0x07, b28=0xA8, b29=0xFC
        // Pack voltage 123.75V (12375 centivolts) = 100% on Aero
        val aeroFrame = makeNosfetFrame(
            voltageHundredthsV = 12375,
            b30 = 0x07,
            b28 = 0xA8,
            b29 = 0xFC,
        )

        val codec = VeteranWheelCodec("11:22:33:44:55:66")
        val state = codec.newState()
        val events = codec.decode(state, aeroFrame)

        val identified = events.filterIsInstance<DecodeEvent.Identified>().firstOrNull()
        val telem = events.filterIsInstance<DecodeEvent.TelemetryUpdate>().firstOrNull()

        assertTrue(identified != null)
        assertEquals("Nosfet AERO", identified!!.identity.modelName)
        assertEquals("502.0.12", identified.identity.firmwareVersion)

        assertTrue(telem != null)
        assertEquals(123.75f, telem!!.snapshot.voltageV)
        assertEquals(100f, telem.snapshot.batteryPercent)
    }

    @Test
    fun `unknown model uses seriesCells fallback or returns null if unspecified`() {
        // Unknown code 999900 = 0x0F41DC -> b30=0x0F, b28=0x41, b29=0xDC
        val unknownFrame = makeNosfetFrame(
            voltageHundredthsV = 14850,
            b30 = 0x0F,
            b28 = 0x41,
            b29 = 0xDC,
        )

        // 1. Without seriesCells -> null
        val codecNoCells = VeteranWheelCodec("11:22:33:44:55:66", profile = VeteranProtocolProfile.MODERN_NOSFET)
        val stateNoCells = codecNoCells.newState()
        val eventsNoCells = codecNoCells.decode(stateNoCells, unknownFrame)
        val telemNoCells = eventsNoCells.filterIsInstance<DecodeEvent.TelemetryUpdate>().first()
        assertNull(telemNoCells.snapshot.batteryPercent)

        // 2. With seriesCells = 36 (e.g. 151.2V pack) -> derives SOC from single-cell curve
        val codecWithCells = VeteranWheelCodec(
            "11:22:33:44:55:66",
            profile = VeteranProtocolProfile.MODERN_NOSFET,
            seriesCells = { 36 },
        )
        val stateWithCells = codecWithCells.newState()
        val eventsWithCells = codecWithCells.decode(stateWithCells, unknownFrame)
        val telemWithCells = eventsWithCells.filterIsInstance<DecodeEvent.TelemetryUpdate>().first()
        assertEquals(100f, telemWithCells.snapshot.batteryPercent)
    }
}
