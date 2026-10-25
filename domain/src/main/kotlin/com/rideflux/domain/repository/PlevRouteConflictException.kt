/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.repository

import com.rideflux.domain.device.PlevCategory

/**
 * A persisted vehicle category contradicts the transport that discovery
 * proved for this address. Routing the wrong transport would silently open
 * the wrong GATT session, so the connection fails closed with a typed reason.
 */
class PlevRouteConflictException(
    val address: String,
    val requestedCategory: PlevCategory,
    val discoveredAs: String,
) : IllegalStateException(
    "Saved $requestedCategory for $address conflicts with $discoveredAs; refusing to route",
)
