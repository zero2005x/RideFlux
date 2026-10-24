/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.garage

import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentityEvidence
import java.util.Locale

/**
 * Per-vehicle calibration offsets the rider has established for this device.
 *
 * These are display/estimation offsets, not a claim that the wheel accepts a
 * calibration write. They are scoped to one saved vehicle so switching
 * vehicles cannot carry one wheel's zero point to another.
 */
data class VehicleCalibration(
    val pitchOffsetDegrees: Float = 0f,
    val rollOffsetDegrees: Float = 0f,
) {
    init {
        require(pitchOffsetDegrees.isFinite() && pitchOffsetDegrees in -30f..30f) {
            "pitchOffsetDegrees must be finite and within -30..30"
        }
        require(rollOffsetDegrees.isFinite() && rollOffsetDegrees in -30f..30f) {
            "rollOffsetDegrees must be finite and within -30..30"
        }
    }
}

/**
 * One saved vehicle in the rider's garage.
 *
 * [address] is the BLE identity and the stable key; [id] is kept as an
 * explicit field so persisted records have a single stable identifier even if
 * a future transport uses a non-MAC address. [family] is an **explicit rider
 * choice or an established family**, not an advertisement guess: the
 * repository still validates it against the discovered GATT table before
 * opening a session.
 *
 * [alertThresholds] is `null` when the vehicle follows the global settings.
 * [calibration] and [displayName] are always vehicle-scoped.
 */
data class VehicleProfile(
    val id: String,
    val address: String,
    val category: PlevCategory,
    val family: WheelFamily?,
    val displayName: String,
    val modelName: String? = null,
    val firmwareVersion: String? = null,
    /**
     * Evidence class that justified [modelName]/[firmwareVersion]. Null means
     * legacy/unprovenance data; it must not be treated as verified identity.
     */
    val identityEvidence: WheelIdentityEvidence? = null,
    /**
     * Advertisement/classifier model used only to choose a scooter transport
     * profile (for example ES2 vs Mi). It is deliberately separate from the
     * verified identity fields and must never authorise controls.
     */
    val transportHint: String? = null,
    val alertThresholds: AlertThresholds? = null,
    val calibration: VehicleCalibration = VehicleCalibration(),
    val notes: String? = null,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(address.isNotBlank()) { "address must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(id == normalizeVehicleAddress(address)) {
            "id must be the normalized address for this profile"
        }
    }
}

/**
 * Stable identifier for a BLE address. Android MACs are case-insensitive; the
 * uppercase form keeps persisted records and in-memory lookups identical.
 */
fun normalizeVehicleAddress(address: String): String = address.trim().uppercase(Locale.ROOT)

/** Build a profile with an id derived from [address]. */
fun vehicleProfile(
    address: String,
    category: PlevCategory,
    family: WheelFamily?,
    displayName: String,
    modelName: String? = null,
    firmwareVersion: String? = null,
    identityEvidence: WheelIdentityEvidence? = null,
    transportHint: String? = null,
    alertThresholds: AlertThresholds? = null,
    calibration: VehicleCalibration = VehicleCalibration(),
    notes: String? = null,
): VehicleProfile = VehicleProfile(
    id = normalizeVehicleAddress(address),
    address = normalizeVehicleAddress(address),
    category = category,
    family = family,
    displayName = displayName.trim().ifBlank { normalizeVehicleAddress(address) },
    modelName = modelName,
    firmwareVersion = firmwareVersion,
    identityEvidence = identityEvidence,
    transportHint = transportHint,
    alertThresholds = alertThresholds,
    calibration = calibration,
    notes = notes,
)
