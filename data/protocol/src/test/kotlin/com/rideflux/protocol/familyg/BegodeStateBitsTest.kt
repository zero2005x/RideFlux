/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyg

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelFault
import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Type `0x04` bytes 14-15: state bits in byte 14, headlight mode in byte 15.
 *
 * Both Begode vendor apps (com.begode.app 0.1.23, com.zzcyi.monotroch 1.0.49) and WheelLog read
 * the fault bits from byte 14 and the light from byte 15. The bit meanings follow the two vendor
 * apps. No capture has a non-zero state byte yet, so the single-bit vectors are synthetic; the
 * real frames below come from WheelLog's GotwayAdapterTest.kt (see NOTICE) and the owner's A2.
 */
class BegodeStateBitsTest {

    /** WheelLog's documented sample frame "A": a parked wheel (see NOTICE). */
    private val parkedLive = hex(
        """
        55 AA 19 F0 00 00 00 00  00 00 01 2C FD CA 00 01
        FF F8 00 18 5A 5A 5A 5A
        """,
    )

    private fun settings(byte14: Int, byte15: Int): ByteArray = hex(
        """
        55 AA 00 00 00 00 00 00  00 00 00 00 00 00
        ${"%02X %02X".format(byte14, byte15)}
        00 00 04 18 5A 5A 5A 5A
        """,
    )

    /** Decodes [frames] on one connection; the first should be a live frame to arm the one-shot alerts. */
    private fun decodeAll(vararg frames: ByteArray): List<DecodeEvent> {
        val codec = BegodeWheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState()
        return frames.flatMap { codec.decode(state, it) }
    }

    private fun List<DecodeEvent>.faults(): Set<WheelFault> =
        filterIsInstance<DecodeEvent.TelemetryUpdate>().last().snapshot.faults ?: emptySet()

    private fun List<DecodeEvent>.alerts(): List<WheelAlert> =
        filterIsInstance<DecodeEvent.Alert>().map { it.alert }

    // ---- which byte ------------------------------------------------------------

    @Test fun `a full headlight byte gives no faults and no alerts`() {
        val events = decodeAll(parkedLive, settings(byte14 = 0x00, byte15 = 0xFF))

        assertEquals(emptySet<WheelFault>(), events.faults())
        assertEquals(emptyList<WheelAlert>(), events.alerts())
    }

    @Test fun `WheelLog's parked 2020 board reports headlight 7 and no faults`() {
        // "decode with 2020 board data": standing still, bytes 14-15 = 00 07.
        val live = hex("55AA19C1000000000000008CF0000001FFF800185A5A5A5A")
        val settings = hex("55AA000060D248001C200064000100070008" + "04185A5A5A5A")
        val decoded = BegodeDecoder.decode(settings) as BegodeFrame.SettingsAndOdometer

        assertEquals(0x00, decoded.stateBits)
        assertEquals(0x07, decoded.headlightMode)
        val events = decodeAll(live, settings)
        assertEquals(emptySet<WheelFault>(), events.faults())
        assertEquals(emptyList<WheelAlert>(), events.alerts())
    }

    @Test fun `the owner's A2 frame decodes as no state bits, light off, device type 18`() {
        // CAP-A type 0x04, the same frame as resources/begode/cap-a-first-cycle.hex line 3.
        val decoded = BegodeDecoder.decode(
            hex("55aa0000000041001c1d002400040000001204185a5a5a5a"),
        ) as BegodeFrame.SettingsAndOdometer

        assertEquals(0, decoded.stateBits)
        assertEquals(0, decoded.headlightMode)
        assertEquals(18, decoded.deviceType)
    }

    @Test fun `over-temperature follows byte 14, not byte 15`() {
        val inByte14 = decodeAll(parkedLive, settings(byte14 = 0x20, byte15 = 0x00))
        assertEquals(setOf(WheelFault.MosOverTemperature), inByte14.faults())
        assertTrue(inByte14.alerts().any { it is WheelAlert.OverTemperature })

        val inByte15 = decodeAll(parkedLive, settings(byte14 = 0x00, byte15 = 0x20))
        assertEquals(emptySet<WheelFault>(), inByte15.faults())
        assertEquals(emptyList<WheelAlert>(), inByte15.alerts())
    }

    // ---- what the bits mean ----------------------------------------------------

    @Test fun `state bits map to the vendor fault set and never to OverSpeed`() {
        // bits 1-6: MOS, gyro, low voltage, over voltage, over temperature, hall
        val faults = decodeAll(settings(byte14 = 0x7E, byte15 = 0x00)).faults()

        assertEquals(
            setOf(
                BegodeWheelCodec.MOS_FAULT,
                WheelFault.ImuSensor,
                WheelFault.LowBattery,
                WheelFault.OverVoltage,
                WheelFault.MosOverTemperature,
                WheelFault.MotorHallSensor,
            ),
            faults,
        )
        assertFalse(faults.contains(WheelFault.OverSpeed))
    }

    @Test fun `MOS and gyro bits raise no speed alarm and no tilt-back`() {
        val events = decodeAll(parkedLive, settings(byte14 = 0x06, byte15 = 0x00))

        assertEquals(setOf(BegodeWheelCodec.MOS_FAULT, WheelFault.ImuSensor), events.faults())
        assertFalse(events.alerts().any { it is WheelAlert.TiltBack })
        assertTrue(events.alerts().single() is WheelAlert.FaultSetChanged)
    }

    @Test fun `locked and the high-power bit are not faults`() {
        val events = decodeAll(parkedLive, settings(byte14 = 0x81, byte15 = 0x00))

        assertEquals(emptySet<WheelFault>(), events.faults())
        assertEquals(emptyList<WheelAlert>(), events.alerts())
    }

    @Test fun `WheelLog's high-power bit at 66 km per h is decoded but is not a fault`() {
        // "decode with new board data 2": riding at about 66.6 km/h, bytes 14-15 = 01 00.
        val live = hex("55aa177007390076001103b6f387148100090018" + "5a5a5a5a")
        val settings = hex("55aa003c24af49001c2000c80000010000120418" + "5a5a5a5a")
        val decoded = BegodeDecoder.decode(settings) as BegodeFrame.SettingsAndOdometer

        assertTrue(decoded.stateHighPowerWarning)
        assertEquals(0, decoded.headlightMode)
        assertEquals(18, decoded.deviceType)
        val events = decodeAll(live, settings)
        assertEquals(emptySet<WheelFault>(), events.faults())
        assertEquals(emptyList<WheelAlert>(), events.alerts())
    }
}
