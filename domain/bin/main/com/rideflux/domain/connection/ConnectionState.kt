/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.connection

import com.rideflux.domain.wheel.WheelFamily

/**
 * Lifecycle state of a single [WheelConnection].
 *
 * The progression is:
 * ```
 * Disconnected  ─►  Connecting  ─►  Handshaking  ─►  Ready  ─►  Disconnected
 *                                      │               │
 *                                      └──► Failed ◄───┘
 * ```
 *
 * [Failed] is terminal for the given connection instance; callers
 * discard the connection and request a new one from the
 * [com.rideflux.domain.repository.WheelRepository].
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()

    /** GATT link-up in progress; no frames exchanged yet. */
    data object Connecting : ConnectionState()

    /**
     * Link up, family identified, bootstrap handshake in progress
     * (`PROTOCOL_SPEC.md` §9.*). Telemetry may already be flowing but
     * may not yet be complete.
     *
     * @property family The family resolved at the link layer; the
     *   identification exchange of that family is still running.
     */
    data class Handshaking(val family: WheelFamily) : ConnectionState()

    /** Handshake complete; telemetry and commands are both available. */
    data object Ready : ConnectionState()

    /**
     * Terminal failure of the connection instance. Reason is a
     * family-agnostic enum so UI code can render appropriately
     * without a big when-branch over family-specific errors.
     */
    data class Failed(val reason: Reason, val message: String? = null) : ConnectionState() {
        enum class Reason {
            BLE_LINK_LOST,
            GATT_ERROR,
            HANDSHAKE_TIMEOUT,
            UNKNOWN_FAMILY,
            CHECKSUM_STORM,
            AUTHENTICATION_FAILED,
            INTERNAL,
        }
    }
}
