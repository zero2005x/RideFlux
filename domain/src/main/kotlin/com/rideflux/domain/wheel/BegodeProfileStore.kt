/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.wheel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * The rider's explicit Begode layout choice, keyed by device address.
 *
 * A missing entry is [BegodeProtocolProfile.UNKNOWN]. The choice does not follow
 * a wheel to a different address, and a new codec reads the current value so a
 * change applies on the next frame and survives process restart when persisted.
 */
interface BegodeProfileStore {
    val profiles: StateFlow<Map<String, BegodeProtocolProfile>>

    suspend fun setProfile(address: String, profile: BegodeProtocolProfile)

    fun profileFor(address: String): BegodeProtocolProfile =
        profiles.value[WheelBatteryPackStore.key(address)] ?: BegodeProtocolProfile.UNKNOWN

    companion object {
        fun key(address: String): String = address.trim().uppercase(Locale.ROOT)

        /** In-memory store for tests and call sites that have not been given a binding. */
        fun empty(): BegodeProfileStore = object : BegodeProfileStore {
            override val profiles: StateFlow<Map<String, BegodeProtocolProfile>> =
                MutableStateFlow(emptyMap())

            override suspend fun setProfile(address: String, profile: BegodeProtocolProfile) = Unit
        }
    }
}
