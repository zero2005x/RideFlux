/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.rideflux.protocol.familyv

/** Explicit parser selection; UNKNOWN safely emits only universal fields without guessing. */
enum class VeteranProtocolProfile { UNKNOWN, LEGACY, MODERN_NOSFET }
