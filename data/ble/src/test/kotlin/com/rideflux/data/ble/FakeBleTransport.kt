/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.transport.BleTransport
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * JVM-only fake [BleTransport] used by [WheelConnectionImplTest].
 *
 * * [emit] pushes a byte frame into [incoming] as if the peer had
 *   sent a GATT notification.
 * * Every [write] call appends the bytes to [writes]. Tests can
 *   clear it and assert on its contents.
 * * [writeFailure], [connectFailure] and [disconnectFailure] let the
 *   test simulate transport-level errors.
 */
internal class FakeBleTransport : BleTransport {

    // A Channel (not SharedFlow) so emitFailure can deliver a real
    // terminal exception to collectors via close(cause) — a SharedFlow
    // never completes, so a mid-stream failure cannot be injected that
    // way. receiveAsFlow drains the channel; close(cause) propagates.
    private val incomingChannel = Channel<ByteArray>(
        capacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    override val incoming: Flow<ByteArray> = incomingChannel.receiveAsFlow()

    val writes: MutableList<ByteArray> = mutableListOf()

    var connectCount: Int = 0
        private set
    var disconnectCount: Int = 0
        private set

    @Volatile var connectFailure: Throwable? = null
    @Volatile var disconnectFailure: Throwable? = null
    @Volatile var writeFailure: Throwable? = null

    override suspend fun connect() {
        connectCount++
        connectFailure?.let { throw it }
    }

    override suspend fun disconnect() {
        disconnectCount++
        disconnectFailure?.let { throw it }
    }

    override suspend fun write(bytes: ByteArray) {
        writeFailure?.let { throw it }
        writes.add(bytes)
    }

    suspend fun emit(bytes: ByteArray) {
        incomingChannel.send(bytes)
    }

    /**
     * Simulate the link failing mid-stream (e.g. GATT disconnect):
     * closes the channel with [cause] so the collecting ingest loop
     * receives the exception, exactly like a real transport whose
     * callbackFlow closes with an error.
     */
    fun emitFailure(cause: Throwable) {
        incomingChannel.close(cause)
    }
}
