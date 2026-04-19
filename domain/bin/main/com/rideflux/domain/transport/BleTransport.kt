/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.transport

import kotlinx.coroutines.flow.Flow

/**
 * BLE port abstraction. Implemented by `:data:ble`; consumed by
 * [com.rideflux.domain.connection.WheelConnection].
 *
 * The transport is deliberately codec-agnostic: it only shuffles
 * opaque byte arrays. Any knowledge of framing, checksums or GATT
 * characteristic selection lives in the codec / BLE-layer bindings
 * configured when the transport instance is created.
 *
 * GATT fragmentation (`PROTOCOL_SPEC.md` §1.3) is preserved: one
 * notification maps to one emission on [incoming]. The connection
 * layer is responsible for reassembly via its codec's state.
 */
interface BleTransport {

    /**
     * Hot stream of raw bytes arriving on the notify characteristic.
     * Completes when the link is closed; errors on link loss.
     */
    val incoming: Flow<ByteArray>

    /**
     * Open the GATT connection, enable notifications, and wait until
     * the link is ready for writes. Idempotent: repeated calls on an
     * already-connected transport return immediately.
     */
    suspend fun connect()

    /** Close the GATT connection. Idempotent. */
    suspend fun disconnect()

    /**
     * Write [bytes] to the configured write characteristic.
     *
     * The call suspends until the GATT write has been acknowledged
     * by the peer. Throws on transport failure; higher layers
     * translate the throwable into a
     * [com.rideflux.domain.command.CommandOutcome.TransportError].
     */
    suspend fun write(bytes: ByteArray)
}
