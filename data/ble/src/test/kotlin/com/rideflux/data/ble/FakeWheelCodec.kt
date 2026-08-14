/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.wheel.WheelFamily

/**
 * Fully programmable [WheelCodec] stub.
 *
 * Behaviour is configured by the test via three function fields:
 *
 * * [onDecode] — invoked for every byte buffer the transport hands
 *   to the connection; returns the [DecodeEvent]s the codec should
 *   emit. Defaults to no-op.
 * * [onEncode] — invoked for every dispatched [WheelCommand]. An
 *   empty-list return is treated by the connection as "unsupported".
 *   Defaults to `emptyList()`.
 * * [onKeepAlive] — per-tick keep-alive frames. Defaults to
 *   `emptyList()` and the connection treats that as "no keep-alive".
 *
 * [handshake] and [keepAliveMillis] control the handshake frames and
 * keep-alive cadence respectively.
 */
internal class FakeWheelCodec(
    override val family: WheelFamily = WheelFamily.G,
    var handshake: List<ByteArray> = emptyList(),
    override val keepAlivePeriodMillis: Long = 0L,
    var onDecode: (ByteArray) -> List<DecodeEvent> = { emptyList() },
    var onEncode: (WheelCommand) -> List<ByteArray> = { emptyList() },
    var onKeepAlive: () -> List<ByteArray> = { emptyList() },
) : WheelCodec {

    object FakeState : WheelCodec.State

    val decodeCalls: MutableList<ByteArray> = mutableListOf()
    val encodeCalls: MutableList<WheelCommand> = mutableListOf()

    override fun newState(): WheelCodec.State = FakeState

    override fun handshakeFrames(state: WheelCodec.State): List<ByteArray> = handshake

    override fun decode(state: WheelCodec.State, bytes: ByteArray): List<DecodeEvent> {
        decodeCalls.add(bytes)
        return onDecode(bytes)
    }

    override fun encode(state: WheelCodec.State, command: WheelCommand): List<ByteArray> {
        encodeCalls.add(command)
        return onEncode(command)
    }

    override fun keepAliveFrames(state: WheelCodec.State): List<ByteArray> = onKeepAlive()
}
