/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.command

import com.rideflux.domain.wheel.WheelCapabilities

/**
 * Map a typed command to the feature flag that authorises it.
 *
 * The mapping is deliberately exhaustive over [WheelCommand]: a new command
 * that is not added here does not compile, which is safer than letting it
 * bypass the capability gate by accident. `Raw` is never authorised by a
 * capability flag; it remains a diagnostic escape hatch whose danger tier is
 * checked separately by the connection.
 */
fun WheelCapabilities.supports(command: WheelCommand): Boolean = when (command) {
    is WheelCommand.SetHeadlight -> headlight
    is WheelCommand.SetLedStrip -> ledStrip
    is WheelCommand.SetDecorativeLights -> decorativeLights
    WheelCommand.Beep -> beep
    WheelCommand.Horn -> horn
    is WheelCommand.SetVolume -> volume
    is WheelCommand.PlaySound -> playSound
    is WheelCommand.SetMaxSpeedKmh -> maxSpeed
    is WheelCommand.SetTiltbackKmh -> tiltback
    is WheelCommand.SetPedalSensitivity -> pedalSensitivity
    is WheelCommand.SetPedalHorizontal -> pedalHorizontal
    is WheelCommand.SetRideMode -> rideModes
    WheelCommand.Calibrate -> calibration
    WheelCommand.PowerOff -> powerOff
    is WheelCommand.UnlockWithPin -> pinUnlock
    is WheelCommand.Raw -> false
}

/**
 * True when this matrix advertises no writable control at all.
 *
 * Used by the dashboard to render a read-only banner instead of an empty row
 * of disabled buttons.
 */
fun WheelCapabilities.isReadOnly(): Boolean =
    !headlight && !horn && !beep && !ledStrip && !decorativeLights &&
        !rideModes && !maxSpeed && !tiltback && !pedalSensitivity &&
        !pedalHorizontal && !calibration && !powerOff && !volume &&
        !playSound && !pinUnlock
