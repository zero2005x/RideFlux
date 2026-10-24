/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.wheel

import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.command.isReadOnly
import com.rideflux.domain.command.supports
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelSupportContractTest {

    private fun full(partial: Boolean = false) = WheelSupport(
        identityEvidence = WheelIdentityEvidence.MODEL_AND_FIRMWARE,
        telemetryCoverage = WheelTelemetryCoverage.MODEL_LAYOUT,
        controlContract = WheelControlContract.FAMILY_CLOSED,
        telemetryComplete = !partial,
    )

    @Test
    fun `family-only identity is limited even if a capability matrix were optimistic`() {
        assertTrue(WheelSupport.FAMILY_ONLY.isLimited)
        assertFalse(full().isLimited)
    }

    @Test
    fun `partial model telemetry is reported as limited`() {
        assertTrue(full(partial = true).isLimited)
    }

    @Test
    fun `capability policy maps each typed command to its feature flag`() {
        val capabilities = WheelCapabilities(
            headlight = true,
            horn = false,
            beep = true,
            ledStrip = false,
            decorativeLights = false,
            rideModes = false,
            maxSpeed = true,
            tiltback = false,
            pedalSensitivity = false,
            pedalHorizontal = false,
            calibration = true,
            powerOff = false,
            volume = false,
            playSound = false,
            pinUnlock = false,
            asyncAlerts = false,
        )
        assertTrue(capabilities.supports(WheelCommand.SetHeadlight(true)))
        assertTrue(capabilities.supports(WheelCommand.Beep))
        assertTrue(capabilities.supports(WheelCommand.SetMaxSpeedKmh(20f)))
        assertTrue(capabilities.supports(WheelCommand.Calibrate))
        assertFalse(capabilities.supports(WheelCommand.Horn))
        assertFalse(capabilities.supports(WheelCommand.PowerOff))
        assertFalse(capabilities.supports(WheelCommand.Raw(byteArrayOf(1))))
        assertFalse(capabilities.isReadOnly())

        val readOnly = capabilities.copy(
            headlight = false,
            beep = false,
            maxSpeed = false,
            calibration = false,
        )
        assertTrue(readOnly.isReadOnly())
    }
}
