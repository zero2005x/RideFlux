/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.wheel

import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * The rider's statement of how many cells in series a wheel's battery has.
 *
 * Some wheels report only the pack voltage, and a voltage means nothing until
 * the number of cells is known: the same 66.9 V is a full 16-cell pack and a
 * nearly empty 20-cell pack. The wheel does not say which it is, so the rider
 * does. Until they do, a family that needs this leaves the battery percentage
 * unknown rather than guessing, and the low-battery alert stays quiet.
 *
 * The answer belongs to one physical wheel, so it is keyed by device address
 * and never carries over to another wheel.
 */
interface WheelBatteryPackStore {

    /** Configured series-cell count by [key]ed wheel address; wheels not listed are unknown. */
    val seriesCells: StateFlow<Map<String, Int>>

    /**
     * Records [cells] for [address], or forgets the answer when [cells] is `null`.
     *
     * Forgetting only affects wheels connected *afterwards*: a live connection keeps
     * the last percentage it derived (an unknown reading inherits the previous one
     * when telemetry is merged). The app therefore only offers choosing another size,
     * not clearing it.
     *
     * @throws IllegalArgumentException when [address] is blank or [cells] is not one
     *   of [SUPPORTED_SERIES_CELLS].
     */
    suspend fun setSeriesCells(address: String, cells: Int?)

    companion object {
        /** Pack sizes the app offers: 67.2 V, 84 V and 100.8 V fully charged. */
        val SUPPORTED_SERIES_CELLS: List<Int> = listOf(16, 20, 24)

        /** Map key for [address]: BLE addresses compare case-insensitively. */
        fun key(address: String): String = address.trim().uppercase(Locale.ROOT)
    }
}
