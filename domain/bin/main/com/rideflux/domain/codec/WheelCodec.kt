/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.codec

import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity

/**
 * Discrete output of [WheelCodec.decode], one per logical frame (or
 * per distinct semantic event inside a frame).
 *
 * Codecs are expected to be *pure*: `decode` receives bytes and
 * returns a list of events. Any stateful buffering (e.g. a GATT
 * reassembly window spanning notifications, or Family-V
 * word-swap state) is retained via the `mutable` state instance that
 * the [com.rideflux.domain.connection.WheelConnection] passes into
 * the codec on each call — see [WheelCodec.newState].
 */
sealed class DecodeEvent {

    /**
     * The codec has produced a newer view of the telemetry snapshot.
     * The connection merges this delta into its `StateFlow`.
     */
    data class TelemetryUpdate(val snapshot: WheelTelemetry) : DecodeEvent()

    /** The codec detected an asynchronous alert record. */
    data class Alert(val alert: WheelAlert) : DecodeEvent()

    /**
     * Identification-handshake frame decoded; [identity] / [capabilities]
     * now describe the device. Emitted exactly once per handshake.
     */
    data class Identified(
        val identity: WheelIdentity,
        val capabilities: WheelCapabilities,
    ) : DecodeEvent()

    /** Codec-level protocol error (checksum storm, bad LEN, etc.). */
    data class Malformed(val reason: String, val offendingBytes: ByteArray?) : DecodeEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Malformed) return false
            return reason == other.reason &&
                (offendingBytes?.contentEquals(other.offendingBytes) ?: (other.offendingBytes == null))
        }
        override fun hashCode(): Int = 31 * reason.hashCode() + (offendingBytes?.contentHashCode() ?: 0)
    }
}

/**
 * Per-family encoder / decoder. A concrete codec wraps the
 * `:data:protocol` family module (e.g. `com.rideflux.protocol.familyi2`)
 * and nothing else.
 *
 * Codecs are **stateless objects**; per-connection state (reassembly
 * buffer, XOR keystream, last-seen telemetry snapshot for delta
 * merging) is held by an opaque [State] allocated by
 * [newState] and passed back into every [decode] / [encode] call.
 * This lets a single codec instance serve many concurrent
 * connections and makes testing trivial.
 */
interface WheelCodec {

    /** Family this codec serves. */
    val family: WheelFamily

    /**
     * Opaque per-connection state. Each concrete codec defines a
     * subtype with its own fields; consumers treat it as opaque.
     */
    interface State

    /** Allocate fresh per-connection state. Pure factory; never I/O. */
    fun newState(): State

    /**
     * Produce the bootstrap byte sequence(s) that
     * [com.rideflux.domain.connection.WheelConnection] must write to
     * the device after GATT link-up to kick off the family's §9
     * identification handshake. May be empty for families that
     * auto-advertise.
     */
    fun handshakeFrames(state: State): List<ByteArray>

    /**
     * Parse [bytes] (one raw BLE chunk) into zero or more
     * [DecodeEvent]s. [state] is mutated in place and retained
     * between calls by the caller.
     */
    fun decode(state: State, bytes: ByteArray): List<DecodeEvent>

    /**
     * Serialise [command] into one or more write frames. Returns an
     * empty list iff the command is unsupported by this family —
     * callers translate that into
     * [com.rideflux.domain.command.CommandOutcome.Unsupported].
     */
    fun encode(state: State, command: WheelCommand): List<ByteArray>

    /**
     * Keep-alive frame(s) the connection should emit periodically
     * (e.g. I2 25 ms ping, N2 session-key refresh), together with
     * the cadence. Empty list means "no keep-alive required".
     */
    fun keepAliveFrames(state: State): List<ByteArray> = emptyList()

    /** Desired keep-alive period in milliseconds; ignored if [keepAliveFrames] is empty. */
    val keepAlivePeriodMillis: Long get() = 0L
}

/**
 * Chooses the right [WheelCodec] for a discovered device.
 *
 * The factory is implemented in `:data:ble` (where GATT UUIDs can be
 * inspected) and in `:data:protocol` it is a simple family→codec map.
 * This split lets the domain layer stay free of BLE types while
 * still owning the selection contract.
 */
interface WheelCodecFactory {
    /** Direct lookup once the family is already known. */
    fun forFamily(family: WheelFamily): WheelCodec

    /**
     * Heuristic lookup from the advertised GATT service UUIDs. The
     * `:data:ble` layer calls this during discovery to decide which
     * family's handshake to run. Returns `null` if no family claims
     * the advertised UUID set.
     */
    fun inferFromGattServiceUuids(uuids: Set<String>): WheelFamily?
}
