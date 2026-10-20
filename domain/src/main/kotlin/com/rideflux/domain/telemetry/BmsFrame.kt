/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.telemetry

/** Passive integrated-BMS frame. Unknown quantities stay null, including zero-filled pages. */
sealed class BmsFrame(
    val packVoltageV: Float? = null,
    val packCurrentA: Float? = null,
    val cellCount: Int? = null,
    val cellVoltagesV: List<Float?>? = null,
    val temperaturesC: List<Float?>? = null,
    val minimumCellVoltageV: Float? = null,
    val maximumCellVoltageV: Float? = null,
    val cellVoltageDifferenceV: Float? = null,
    val stateOfChargePercent: Float? = null,
) {
    /** Experimental: complete wire frame retained without assigning disputed physical meanings. */
    data class Raw(
        val typeCode: Int,
        val subIndex: Int,
        val bytes: List<Byte>,
    ) : BmsFrame()
}
