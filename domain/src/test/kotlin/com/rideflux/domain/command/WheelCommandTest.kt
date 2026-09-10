/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Construction-time validation is the first line of defence for every
 * command: a value that gets past it reaches a family wire encoder that
 * will happily saturate or wrap it into a plausible-looking frame.
 */
class WheelCommandTest {

    // ---- Volume ---------------------------------------------------------

    @Test
    fun `volume accepts the full percentage range`() {
        for (percent in listOf(0, 1, 50, 99, 100)) {
            assertEquals(percent, WheelCommand.SetVolume(percent).percent)
        }
    }

    @Test
    fun `volume rejects values outside zero to one hundred`() {
        assertThrows(IllegalArgumentException::class.java) { WheelCommand.SetVolume(-1) }
        assertThrows(IllegalArgumentException::class.java) { WheelCommand.SetVolume(101) }
    }

    // ---- Speed-valued commands ------------------------------------------

    @Test
    fun `max speed accepts the documented bound`() {
        assertEquals(0f, WheelCommand.SetMaxSpeedKmh(0f).kmh)
        assertEquals(
            WheelCommand.MAX_SPEED_LIMIT_KMH,
            WheelCommand.SetMaxSpeedKmh(WheelCommand.MAX_SPEED_LIMIT_KMH).kmh,
        )
    }

    @Test
    fun `max speed rejects negatives and values past the bound`() {
        assertThrows(IllegalArgumentException::class.java) { WheelCommand.SetMaxSpeedKmh(-0.1f) }
        assertThrows(IllegalArgumentException::class.java) {
            WheelCommand.SetMaxSpeedKmh(WheelCommand.MAX_SPEED_LIMIT_KMH + 1f)
        }
    }

    /**
     * The KDoc calls this out specifically: NaN falls out of the range
     * comparison on its own, but POSITIVE_INFINITY does not, so the
     * explicit isFinite() check is what stops it.
     */
    @Test
    fun `max speed rejects non-finite values`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { WheelCommand.SetMaxSpeedKmh(bad) }
        }
    }

    @Test
    fun `tiltback is validated on the same terms as max speed`() {
        assertEquals(30f, WheelCommand.SetTiltbackKmh(30f).kmh)
        assertThrows(IllegalArgumentException::class.java) { WheelCommand.SetTiltbackKmh(-1f) }
        assertThrows(IllegalArgumentException::class.java) {
            WheelCommand.SetTiltbackKmh(Float.POSITIVE_INFINITY)
        }
    }

    // ---- Pedal angle ----------------------------------------------------

    @Test
    fun `pedal angle allows negatives but not non-finite values`() {
        // A negative angle is a legitimate backward pedal tilt.
        assertEquals(-3.5f, WheelCommand.SetPedalHorizontal(-3.5f).angleDegrees)
        assertThrows(IllegalArgumentException::class.java) {
            WheelCommand.SetPedalHorizontal(Float.NaN)
        }
    }

    // ---- PIN ------------------------------------------------------------

    @Test
    fun `pin accepts four to eight digits`() {
        assertEquals("1234", WheelCommand.UnlockWithPin("1234").pin)
        assertEquals("12345678", WheelCommand.UnlockWithPin("12345678").pin)
    }

    @Test
    fun `pin rejects the wrong length`() {
        assertThrows(IllegalArgumentException::class.java) { WheelCommand.UnlockWithPin("123") }
        assertThrows(IllegalArgumentException::class.java) { WheelCommand.UnlockWithPin("123456789") }
        assertThrows(IllegalArgumentException::class.java) { WheelCommand.UnlockWithPin("") }
    }

    @Test
    fun `pin rejects non-digits`() {
        assertThrows(IllegalArgumentException::class.java) { WheelCommand.UnlockWithPin("12a4") }
        assertThrows(IllegalArgumentException::class.java) { WheelCommand.UnlockWithPin("12 4") }
        assertThrows(IllegalArgumentException::class.java) { WheelCommand.UnlockWithPin("-123") }
    }

    /**
     * The outcome wrappers retain the failing command, and those end up
     * in logs — so the PIN must never appear in `toString()`.
     */
    @Test
    fun `pin is redacted in toString`() {
        val command = WheelCommand.UnlockWithPin("48213")
        assertFalse("PIN leaked: $command", command.toString().contains("48213"))
        assertEquals("UnlockWithPin(pin=***)", command.toString())
    }

    @Test
    fun `pin stays redacted when wrapped in a failure outcome`() {
        val outcome = CommandOutcome.TransportError(WheelCommand.UnlockWithPin("48213"), null)
        assertFalse("PIN leaked: $outcome", outcome.toString().contains("48213"))
    }

    // ---- Raw ------------------------------------------------------------

    @Test
    fun `raw commands compare by content, not reference`() {
        val a = WheelCommand.Raw(byteArrayOf(1, 2, 3))
        val b = WheelCommand.Raw(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, WheelCommand.Raw(byteArrayOf(1, 2, 4)))
    }

    // ---- Outcomes -------------------------------------------------------

    @Test
    fun `unsupported retains the command so callers can report which failed`() {
        val command = WheelCommand.SetLedStrip(on = true)
        val outcome = CommandOutcome.Unsupported(command)
        assertEquals(command, outcome.command)
    }

    @Test
    fun `invalid argument carries an explanatory message`() {
        val outcome = CommandOutcome.InvalidArgument(
            command = WheelCommand.SetRideMode(9),
            message = "mode 9 unknown for family K",
        )
        assertTrue(outcome.message.isNotBlank())
    }

    @Test
    fun `success is a singleton`() {
        assertTrue(CommandOutcome.Success === CommandOutcome.Success)
    }

    @Test
    fun `parameterless commands are singletons`() {
        assertTrue(WheelCommand.Beep === WheelCommand.Beep)
        assertTrue(WheelCommand.Horn === WheelCommand.Horn)
        assertTrue(WheelCommand.Calibrate === WheelCommand.Calibrate)
        assertTrue(WheelCommand.PowerOff === WheelCommand.PowerOff)
        // Cast to Any so Kotlin picks assertNotEquals(Object, Object)
        // rather than the primitive-numeric overload.
        assertNotEquals(WheelCommand.Beep as Any, WheelCommand.Horn as Any)
    }
}
