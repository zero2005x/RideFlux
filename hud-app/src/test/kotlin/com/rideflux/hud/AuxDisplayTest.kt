/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud

import com.rideflux.data.bridge.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A gear is not a percentage. The slot meanings differ per category, so the
 * decision must come from the frame's vehicle type, not from which value is
 * present.
 */
class AuxDisplayTest {

    @Test
    fun `a scooter with an ESC temperature shows celsius`() {
        assertEquals(
            AuxDisplay.Celsius(41f),
            AuxDisplay.forFrame(VehicleType.SCOOTER, 41f, 2),
        )
    }

    @Test
    fun `a scooter without a temperature shows its gear`() {
        assertEquals(
            AuxDisplay.Gear(2),
            AuxDisplay.forFrame(VehicleType.SCOOTER, null, 2),
        )
    }

    @Test
    fun `a scooter gear is never rendered as a percentage`() {
        val display = AuxDisplay.forFrame(VehicleType.SCOOTER, null, 2)

        assertEquals(false, display is AuxDisplay.Percent)
    }

    @Test
    fun `a scooter with neither value shows nothing`() {
        assertNull(AuxDisplay.forFrame(VehicleType.SCOOTER, null, null))
    }

    @Test
    fun `a non-finite scooter temperature falls back to the gear`() {
        assertEquals(
            AuxDisplay.Gear(1),
            AuxDisplay.forFrame(VehicleType.SCOOTER, Float.NaN, 1),
        )
    }

    @Test
    fun `a wheel shows PWM percent`() {
        assertEquals(
            AuxDisplay.Percent(61),
            AuxDisplay.forFrame(VehicleType.WHEEL, 12.3f, 61),
        )
    }

    @Test
    fun `a wheel without PWM shows nothing`() {
        assertNull(AuxDisplay.forFrame(VehicleType.WHEEL, 12.3f, null))
    }

    @Test
    fun `a legacy frame and a BMS show nothing`() {
        assertNull(AuxDisplay.forFrame(VehicleType.UNKNOWN, null, 5))
        assertNull(AuxDisplay.forFrame(VehicleType.BMS, -4.2f, null))
    }
}
