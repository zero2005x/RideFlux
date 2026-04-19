/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.transport.BleTransport
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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

    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

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
        _incoming.emit(bytes)
    }
}
