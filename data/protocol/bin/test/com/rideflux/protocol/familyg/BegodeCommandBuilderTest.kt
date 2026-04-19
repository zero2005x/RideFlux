/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins Family G command-builder output to the ASCII byte sequences
 * listed in `PROTOCOL_SPEC.md` §9.1 and §10.1.
 */
class BegodeCommandBuilderTest {

    @Test fun `identification requests are single ASCII bytes`() {
        assertArrayEquals(byteArrayOf('N'.code.toByte()), BegodeCommandBuilder.requestName())
        assertArrayEquals(byteArrayOf('V'.code.toByte()), BegodeCommandBuilder.requestFirmware())
    }

    @Test fun `beep is ASCII lowercase b`() {
        assertArrayEquals(byteArrayOf(0x62), BegodeCommandBuilder.beep())
    }

    @Test fun `unit switches use m and g`() {
        assertArrayEquals(byteArrayOf(0x6D), BegodeCommandBuilder.unitsMiles())
        assertArrayEquals(byteArrayOf(0x67), BegodeCommandBuilder.unitsKilometres())
    }

    @Test fun `light mode encodes E Q T`() {
        assertArrayEquals(byteArrayOf(0x45), BegodeCommandBuilder.lightMode(BegodeCommandBuilder.LightMode.OFF))
        assertArrayEquals(byteArrayOf(0x51), BegodeCommandBuilder.lightMode(BegodeCommandBuilder.LightMode.ON))
        assertArrayEquals(byteArrayOf(0x54), BegodeCommandBuilder.lightMode(BegodeCommandBuilder.LightMode.STROBE))
    }

    @Test fun `roll angle encodes lt eq gt`() {
        assertArrayEquals(byteArrayOf('<'.code.toByte()), BegodeCommandBuilder.rollAngle(BegodeCommandBuilder.RollAngle.SOFT))
        assertArrayEquals(byteArrayOf('='.code.toByte()), BegodeCommandBuilder.rollAngle(BegodeCommandBuilder.RollAngle.MEDIUM))
        assertArrayEquals(byteArrayOf('>'.code.toByte()), BegodeCommandBuilder.rollAngle(BegodeCommandBuilder.RollAngle.HARD))
    }

    @Test fun `pedals mode encodes h f s`() {
        assertArrayEquals(byteArrayOf('h'.code.toByte()), BegodeCommandBuilder.pedalsMode(BegodeCommandBuilder.PedalsMode.HARD))
        assertArrayEquals(byteArrayOf('f'.code.toByte()), BegodeCommandBuilder.pedalsMode(BegodeCommandBuilder.PedalsMode.MEDIUM))
        assertArrayEquals(byteArrayOf('s'.code.toByte()), BegodeCommandBuilder.pedalsMode(BegodeCommandBuilder.PedalsMode.SOFT))
    }

    @Test fun `alarm mode encodes o u i`() {
        assertArrayEquals(byteArrayOf('o'.code.toByte()), BegodeCommandBuilder.alarmMode(BegodeCommandBuilder.AlarmMode.LEVEL_2))
        assertArrayEquals(byteArrayOf('u'.code.toByte()), BegodeCommandBuilder.alarmMode(BegodeCommandBuilder.AlarmMode.LEVEL_1))
        assertArrayEquals(byteArrayOf('i'.code.toByte()), BegodeCommandBuilder.alarmMode(BegodeCommandBuilder.AlarmMode.OFF))
    }

    @Test fun `wheel calibration is two ASCII bytes c y`() {
        val bytes = BegodeCommandBuilder.wheelCalibration()
        assertEquals(2, bytes.size)
        assertEquals('c'.code.toByte(), bytes[0])
        assertEquals('y'.code.toByte(), bytes[1])
    }
}
