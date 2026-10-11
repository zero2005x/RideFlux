/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.wheel

/**
 * How a Begode live frame's ambiguous words are interpreted.
 *
 * Selection is an explicit rider statement for one wheel address. Voltage, BLE
 * address, a constant 13.7, packet presence and an advertised name are not evidence
 * for choosing a profile.
 */
enum class BegodeProtocolProfile {
    /**
     * No layout has been confirmed for this wheel. Fields whose meaning is disputed
     * stay null instead of being filled from a family-wide guess.
     */
    UNKNOWN,

    /**
     * Layout established for the captured Begode A2 profile only.
     *
     * Packet `0x00` offset 14 is a packed mode/config word, not PWM. The IMU raw
     * word uses the MPU6050 formula and is not a MOS temperature. PWM is taken
     * only from packet `0x07` offset 8 when that word is in range. That PWM scale
     * is vendor-derived; the capturing session was stationary, so a non-zero
     * loaded reading is not yet confirmed.
     */
    A2_CAPTURED,

    /**
     * Explicit opt-in to the historical documented live-frame mapping
     * (offset 14 as PWM tenths, MPU6500 temperature published as both IMU and MOS).
     * Never selected automatically.
     */
    LEGACY_DOCUMENTED,
}
