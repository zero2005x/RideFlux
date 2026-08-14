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
     * Repeated calls for the same [address] return a handle to the
     * same live connection so that multiple feature modules can share
     * one device. The connection is closed when its reference count
     * drops to zero (all callers have called
     * [WheelConnection.close]).
     *
     * Callers MUST call [WheelConnection.close] on the returned handle
     * exactly once when they are done; each handle is reference-counted
     * and the underlying connection is torn down only after the last
     * holder releases it. The close/connect transition for the same
     * address is serialized: a fresh connection is established once the
     * count drops to zero, so no caller is ever handed a connection
     * that has just been closed, and duplicate instances for the same
     * address are never created.
     *
     * If [expectedFamily] is non-null, the repository runs only that
     * family's handshake and does not fall back to UUID probing; if
     * the hint turns out to be wrong the connection will fail and the
     * failure is surfaced through
     * [com.rideflux.domain.connection.WheelConnection.state] as
     * [com.rideflux.domain.connection.ConnectionState.Failed]. Pass
     * `null` when the family is only a best-guess.
     */
    suspend fun connect(
        address: String,
        expectedFamily: WheelFamily? = null,
    ): WheelConnection

    /**
     * StateFlow of live connections keyed by address. UI modules
     * that need a global "which wheel is active" view subscribe here.
     *
     * Connections obtained from this flow are the shared, live
     * instances: they MUST NOT be `close()`d and MUST NOT be retained
     * beyond the emission, because closing or leaking a shared handle
     * corrupts the reference count for every other user of that device.
     * Treat the map as read-only telemetry (state / identity) only.
     */
    fun activeConnections(): Flow<Map<String, WheelConnection>>
}
