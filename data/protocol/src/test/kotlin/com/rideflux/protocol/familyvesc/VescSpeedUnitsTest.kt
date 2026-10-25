/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyvesc

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-unit regression for the VESC speed field.
 *
 * The fixed frame below is an official-conformant `COMM_GET_VALUES_SETUP`
 * reply (75 bytes, 70-byte payload, CRC `0x83A9`). Its speed word is
 * `0x00002710` = 10000; with the firmware's 1e3 scale that is **10 m/s**,
 * which the domain must publish as **36 km/h**.
 *
 * Expected values are literals from the fixed bytes; neither the product
 * encoder nor [VescSetupCodec] computes them. The frame's CRC was verified
 * independently with `binascii.crc_hqx(payload, 0)`.
 *
 * Official source (bldc `abc9217fb2b7ee3821f1742bc32b269173643319`):
 * `comm/commands.c` serialises `mc_interface_get_speed()` with scale 1e3 and
 * `motor/mc_interface.c` defines that function in m/s.
 */
class VescSpeedUnitsTest {

    /** 10 m/s on the wire: 0x00002710 at payload offset 19. */
    private val officialFrame = hex(
        "02462F00FD0000000004D20000023701F4000007D00000271001F8032000" +
            "000000000000000000000000000000000000000000000000000000000000" +
            "00000000000030390000000083A903",
    )

    private fun telemetryOf(frame: ByteArray): WheelTelemetry {
        val codec = VescWheelCodec("AA:BB:CC:DD:EE:FF")
        val events = codec.decode(codec.newState(), frame)
        return events.filterIsInstance<DecodeEvent.TelemetryUpdate>().single().snapshot
    }

    /**
     * Replace the big-endian speed word in the fixed payload and re-seal the
     * CRC. Only the CRC primitive is shared with production; every expected
     * speed below is a literal.
     */
    private fun withSpeedRaw(raw: Int): ByteArray {
        val frame = officialFrame.copyOf()
        frame[2 + 19] = (raw ushr 24).toByte()
        frame[2 + 20] = (raw ushr 16).toByte()
        frame[2 + 21] = (raw ushr 8).toByte()
        frame[2 + 22] = raw.toByte()
        val payload = frame.copyOfRange(2, 2 + 70)
        val crc = VescSetupCodec.crc16Xmodem(payload)
        frame[72] = (crc ushr 8).toByte()
        frame[73] = crc.toByte()
        return frame
    }

    @Test
    fun `official 10 m per s wire value publishes 36 km per h`() {
        // The codec keeps the wire unit, so the name and the number agree.
        val values = requireNotNull(VescSetupCodec.decode(officialFrame))
        assertEquals(10f, values.speedMetresPerSecond, 0.001f)
        assertEquals(50.4f, values.inputVoltageV, 0.01f)
        assertEquals(80f, values.batteryPercent, 0.01f)

        // …and the domain boundary converts exactly once.
        val telemetry = telemetryOf(officialFrame)
        assertEquals(36f, requireNotNull(telemetry.speedKmh), 0.001f)
        assertEquals(50.4f, requireNotNull(telemetry.voltageV), 0.01f)
        assertEquals(80f, requireNotNull(telemetry.batteryPercent), 0.01f)
    }

    @Test
    fun `a decoded setup frame is a fresh speed sample for the interlock`() {
        val codec = VescWheelCodec("AA:BB:CC:DD:EE:FF")
        val update = codec.decode(codec.newState(), withSpeedRaw(0))
            .filterIsInstance<DecodeEvent.TelemetryUpdate>().single()
        assertEquals(0f, requireNotNull(update.snapshot.speedKmh), 0.001f)
        assertTrue("every published VESC record decodes speed", update.freshSpeed)
    }

    @Test
    fun `standing still publishes zero in both units`() {
        val frame = withSpeedRaw(0)
        assertEquals(0f, requireNotNull(VescSetupCodec.decode(frame)).speedMetresPerSecond, 0.001f)
        assertEquals(0f, requireNotNull(telemetryOf(frame).speedKmh), 0.001f)
    }

    @Test
    fun `reverse speed keeps its sign through the conversion`() {
        val frame = withSpeedRaw(-10_000) // -10 m/s
        assertEquals(-10f, requireNotNull(VescSetupCodec.decode(frame)).speedMetresPerSecond, 0.001f)
        assertEquals(-36f, requireNotNull(telemetryOf(frame).speedKmh), 0.001f)
    }

    @Test
    fun `the plausibility range is expressed in metres per second`() {
        // 69 m/s ≈ 248.4 km/h is inside the 250 km/h bound…
        val inRange = withSpeedRaw(69_000)
        assertEquals(248.4f, requireNotNull(telemetryOf(inRange).speedKmh), 0.01f)

        // …while 70 m/s ≈ 252 km/h is outside it and is rejected outright.
        assertNull(VescSetupCodec.decode(withSpeedRaw(70_000)))
    }
}
