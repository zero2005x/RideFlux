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
     *
     * Each emission MUST deliver a fresh copy of the bytes (a BLE
     * implementation may otherwise reuse a receive buffer for
     * consecutive notifications). The flow completes cleanly when the
     * link is closed and errors on link loss; a later [connect] on the
     * same transport must not resume emissions on this same [Flow]
     * instance — consumers should treat a completed flow as terminal.
     */
    val incoming: Flow<ByteArray>

    /**
     * Open the GATT connection, enable notifications, and wait until
     * the link is ready for writes. Idempotent for *sequential* calls:
     * repeated calls on an already-connected transport return
     * immediately. Implementations must serialize connect/disconnect
     * (e.g. with a Mutex or a single-threaded dispatcher), because
     * concurrent invocations would drive the GATT stack in an
     * inconsistent state.
     */
    suspend fun connect()

    /**
     * Close the GATT connection. Idempotent.
     *
     * NOTE: implementations may make the transport single-use — i.e. a
     * subsequent [connect] throws once [disconnect] has been called.
     * Consumers must not assume they can reconnect a disconnected
     * transport; allocate a fresh instance when reconnecting.
     */
    suspend fun disconnect()

    /**
     * Write [bytes] to the configured write characteristic.
     *
     * The call suspends until the GATT write has been acknowledged by
     * the peer. Implementations MUST place an upper bound on this wait
     * and throw a timeout error after a bounded interval, so a stalled
     * or half-open link can never suspend the caller indefinitely.
     * Throws on transport failure; higher layers translate the
     * throwable into a
     * [com.rideflux.domain.command.CommandOutcome.TransportError].
     *
     * Buffer ownership: the caller must not mutate [bytes] until this
     * call returns, and the implementation must neither mutate nor
     * retain [bytes] after returning. If the payload has to outlive the
     * call (queued or retried writes), the implementation MUST take a
     * defensive copy before returning, so that caller-side buffer reuse
     * can never corrupt a pending write.
     */
    suspend fun write(bytes: ByteArray)
}
