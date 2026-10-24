/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud

import com.rideflux.data.bridge.VehicleType

/**
 * Which category-specific value the HUD's right column should show.
 *
 * The three aux slots carry different meanings per category, so the choice is
 * made from the frame's [VehicleType] rather than from which slot happens to
 * be non-null. A scooter's `aux2` is a gear — rendering it as a percentage
 * would tell a rider "2%" where the scooter reports "gear 2".
 */
internal sealed interface AuxDisplay {
    /** A temperature in °C: a scooter's ESC temperature. */
    data class Celsius(val value: Float) : AuxDisplay

    /** An unsigned percentage: a wheel's PWM duty. */
    data class Percent(val value: Int) : AuxDisplay

    /** A scooter's selected gear; never a percentage. */
    data class Gear(val value: Int) : AuxDisplay

    companion object {
        /**
         * Pick the value for [vehicleType], or `null` when the category has
         * nothing to show.
         *
         * A wheel shows PWM from `aux2`; a scooter prefers its ESC
         * temperature from `aux1` and otherwise falls back to the gear from
         * `aux2`. A frame with no type (a legacy v2 frame, or a BMS) shows
         * nothing, because the slot meanings would be a guess.
         */
        fun forFrame(vehicleType: VehicleType, aux1: Float?, aux2: Int?): AuxDisplay? =
            when (vehicleType) {
                VehicleType.SCOOTER -> when {
                    aux1 != null && aux1.isFinite() -> Celsius(aux1)
                    aux2 != null -> Gear(aux2)
                    else -> null
                }
                VehicleType.WHEEL -> aux2?.let { Percent(it) }
                VehicleType.UNKNOWN, VehicleType.BMS -> null
            }
    }
}
