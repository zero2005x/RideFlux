/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.garage

import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentityEvidence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence boundary for the rider's saved vehicles.
 *
 * Implementations must persist [VehicleProfile] records across process death.
 * [familyHints] is a synchronous snapshot used by the BLE repository before it
 * opens a connection: a saved family is only a hint and discovery still
 * decides. [profiles] is the ordered list shown by the scanner/garage UI.
 */
interface VehicleProfileStore {
    val profiles: StateFlow<List<VehicleProfile>>

    /** address (normalized) → explicitly saved family, for pre-connect hints. */
    val familyHints: StateFlow<Map<String, WheelFamily>>

    suspend fun current(): List<VehicleProfile>

    suspend fun profile(address: String): VehicleProfile?

    suspend fun upsert(profile: VehicleProfile)

    /**
     * Atomically read the latest persisted record, apply [transform], and
     * write the result. Implementations that can serialize this against
     * their storage must do so; a default read-then-[upsert] is only a
     * compatibility fallback for simple in-memory fakes.
     */
    suspend fun update(address: String, transform: (VehicleProfile) -> VehicleProfile): VehicleProfile? {
        val key = normalizeVehicleAddress(address)
        val current = profile(key) ?: return null
        val next = transform(current)
        upsert(next)
        return next
    }

    suspend fun remove(address: String)

    companion object {
        /** A no-op store for tests and for call sites that predate the garage. */
        fun empty(): VehicleProfileStore = InMemoryVehicleProfileStore()
    }
}

/**
 * Persist identity that a live session actually decoded. The rider's alias,
 * alert override, calibration and notes are preserved; [family] only fills a
 * missing value and never replaces an explicit rider choice. A null model or
 * firmware is ignored so a weak discovery hint cannot upgrade a profile.
 */
suspend fun VehicleProfileStore.updateIdentifiedIdentity(
    address: String,
    modelName: String?,
    firmwareVersion: String?,
    family: WheelFamily?,
    evidence: WheelIdentityEvidence?,
): VehicleProfile? {
    // Only model+firmware evidence may occupy the verified identity fields.
    // A FAMILY_ONLY or MODEL_ONLY event is a routing hint, not a saved model.
    if (evidence != WheelIdentityEvidence.MODEL_AND_FIRMWARE) return null
    if (modelName.isNullOrBlank() || firmwareVersion.isNullOrBlank()) return null
    return update(address) { current ->
        current.copy(
            modelName = modelName,
            firmwareVersion = firmwareVersion,
            identityEvidence = evidence,
            family = current.family ?: family,
        )
    }
}

/**
 * Process-local implementation used by tests and as the default for call
 * sites that do not inject a persistent store. Production wiring uses the
 * DataStore-backed implementation in `:data:preferences`.
 */
class InMemoryVehicleProfileStore(
    initial: List<VehicleProfile> = emptyList(),
) : VehicleProfileStore {
    private val editMutex = Mutex()
    private val _profiles = MutableStateFlow(initial.sortedBy { it.displayName })
    override val profiles: StateFlow<List<VehicleProfile>> = _profiles.asStateFlow()

    private val _familyHints = MutableStateFlow(familyHintsOf(_profiles.value))
    override val familyHints: StateFlow<Map<String, WheelFamily>> = _familyHints.asStateFlow()

    override suspend fun current(): List<VehicleProfile> = _profiles.value

    override suspend fun profile(address: String): VehicleProfile? {
        val key = normalizeVehicleAddress(address)
        return _profiles.value.firstOrNull { it.address == key }
    }

    override suspend fun upsert(profile: VehicleProfile) = editMutex.withLock {
        publish(_profiles.value.filterNot { it.id == profile.id } + profile)
    }

    override suspend fun update(
        address: String,
        transform: (VehicleProfile) -> VehicleProfile,
    ): VehicleProfile? = editMutex.withLock {
        val key = normalizeVehicleAddress(address)
        val current = _profiles.value.firstOrNull { it.address == key } ?: return@withLock null
        val next = transform(current)
        publish(_profiles.value.filterNot { it.id == next.id } + next)
        next
    }

    override suspend fun remove(address: String) = editMutex.withLock {
        val key = normalizeVehicleAddress(address)
        publish(_profiles.value.filterNot { it.address == key })
    }

    private fun publish(next: List<VehicleProfile>) {
        val sorted = next.sortedBy { it.displayName }
        _profiles.value = sorted
        _familyHints.value = familyHintsOf(sorted)
    }

    private fun familyHintsOf(profiles: List<VehicleProfile>): Map<String, WheelFamily> =
        profiles.mapNotNull { profile -> profile.family?.let { profile.address to it } }.toMap()
}
