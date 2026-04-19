/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.repository

import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.wheel.WheelFamily
import kotlinx.coroutines.flow.Flow

/**
 * A BLE device observed during scanning, annotated with the family
 * guess inferred from its advertised GATT service UUIDs (when
 * available).
 *
 * [family] is best-effort; some vendors advertise generic UUIDs and
 * the real family is only determined after the bootstrap handshake.
 * Consumers must therefore treat [family] as a hint for UI grouping
 * / icons and not rely on it for decoding.
 */
data class DiscoveredWheel(
    val address: String,
    val displayName: String?,
    val rssi: Int?,
    val family: WheelFamily?,
)

/**
 * Top-level repository through which UI and feature modules obtain
 * [WheelConnection] instances. Hides BLE scanning, permission
 * plumbing, family detection and reconnection logic.
 *
 * Exactly one repository instance exists per app process; it is
 * injected via DI.
 */
interface WheelRepository {

    /**
     * Hot stream of discovered devices. Scanning is ref-counted:
     * subscribe to start, cancel to stop. Emits fresh device sets as
     * advertising is observed; a device absent from a subsequent
     * emission is considered gone from the scan window.
     */
    fun scan(): Flow<List<DiscoveredWheel>>

    /**
     * Open a connection to [address]. Suspends until the underlying
     * transport is connecting (not until handshake is complete);
     * observe the returned connection's
     * [com.rideflux.domain.connection.WheelConnection.state] to wait
     * for readiness.
     *
     * Repeated calls for the same [address] return the same live
     * connection so that multiple feature modules can share one
     * device. The connection is closed when its reference count
     * drops to zero (all callers have called
     * [WheelConnection.close]).
     *
     * If [expectedFamily] is non-null, the repository runs only that
     * family's handshake; otherwise it probes GATT UUIDs to pick one.
     */
    suspend fun connect(
        address: String,
        expectedFamily: WheelFamily? = null,
    ): WheelConnection

    /**
     * StateFlow of live connections keyed by address. UI modules
     * that need a global "which wheel is active" view subscribe here.
     */
    fun activeConnections(): Flow<Map<String, WheelConnection>>
}
