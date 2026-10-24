/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of RideFlux. It is licensed under the GNU General
 * Public License, version 3 or (at your option) any later version.
 * See the LICENSE file in the repository root for the full text.
 */
package com.rideflux.data.ble

import com.rideflux.domain.transport.BleTransport

/**
 * A [BleTransport] that publishes the GATT table it discovered and the
 * session resolution derived from it.
 *
 * The connection layer reads [resolution] once, after
 * [BleTransport.connect] returns, and builds the codec from it. That is the
 * only point where the discovered table exists and no telemetry has been
 * parsed yet, so it is the only safe place to choose a family.
 */
interface NegotiatingTransport : BleTransport {

    /** The discovered table, or `null` before discovery. */
    val gattSnapshot: GattSnapshot?

    /**
     * Resolution for this session. `null` when the transport has no resolver
     * (legacy construction) or before discovery.
     */
    val resolution: WheelResolution?
}

/**
 * Thrown by [NegotiatingTransport.connect] when the discovered table cannot
 * produce a usable family (ambiguous, unsupported or contradictory).
 *
 * Carrying the [resolution] lets the connection surface the diagnostic
 * instead of a generic "link lost", and guarantees that no bootstrap frame
 * for a guessed family is written.
 */
class FamilyResolutionException(
    val resolution: WheelResolution,
) : IllegalStateException(
    "wheel family unresolved (${resolution.confidence}): ${resolution.reason}",
)
