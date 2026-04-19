/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.connection

import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelIdentity
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The primary family-agnostic handle that feature / UI modules hold
 * to interact with a single wheel.
 *
 * A `WheelConnection` merges three things into one unified reactive
 * API:
 *
 * 1. a [com.rideflux.domain.transport.BleTransport] (the bytes pipe),
 * 2. a [com.rideflux.domain.codec.WheelCodec] (the family-specific
 *    parser / builder), and
 * 3. per-connection state (reassembly buffers, keep-alive timers,
 *    last-seen telemetry for delta merging).
 *
 * All flows are *hot* and scoped to the connection's own
 * `CoroutineScope`. When the connection terminates
 * ([state] transitions to [ConnectionState.Failed] or
 * [ConnectionState.Disconnected] after [close]), the hot scope is
 * cancelled and every flow stops emitting.
 *
 * ### Command semantics
 *
 * Commands are dispatched via [dispatch] (for the full typed sealed
 * class) or the convenience `suspend fun`s below (for the common
 * cases). Both paths return a [CommandOutcome]; none of them throw
 * except for [IllegalArgumentException] on locally-validated
 * parameters (e.g. negative speeds). A `Success` outcome means the
 * bytes reached the BLE stack, **not** that the device acted on
 * them — confirm by observing [telemetry].
 */
interface WheelConnection {

    // ---- Identity / lifecycle ------------------------------------------

    /** Identity resolved during the handshake; `null` until then. */
    val identity: StateFlow<WheelIdentity?>

    /** Capability matrix; `null` until handshake completes. */
    val capabilities: StateFlow<WheelCapabilities?>

    /** Connection lifecycle, see [ConnectionState]. */
    val state: StateFlow<ConnectionState>

    // ---- Unified telemetry ---------------------------------------------

    /**
     * Authoritative snapshot of the wheel's current state. Starts at
     * [WheelTelemetry.EMPTY] and is refreshed by the codec on every
     * decoded frame. Use this flow when you need several fields
     * coherently (e.g. a dashboard tile); use the derived
     * single-field flows below when you only need one value and want
     * structural-equality dedup.
     */
    val telemetry: StateFlow<WheelTelemetry>

    /** Derived StateFlow: latest [WheelTelemetry.speedKmh], or `null` until first frame. */
    val speedKmh: StateFlow<Float?>

    /** Derived StateFlow: latest [WheelTelemetry.voltageV], or `null`. */
    val voltageV: StateFlow<Float?>

    /** Derived StateFlow: latest [WheelTelemetry.batteryPercent], or `null`. */
    val batteryPercent: StateFlow<Float?>

    /** Derived StateFlow: latest [WheelTelemetry.currentA], or `null`. */
    val currentA: StateFlow<Float?>

    /** Derived StateFlow: latest [WheelTelemetry.mosTemperatureC], or `null`. */
    val mosTemperatureC: StateFlow<Float?>

    /** Derived StateFlow: latest [WheelTelemetry.totalDistanceMetres], or `null`. */
    val totalDistanceMetres: StateFlow<Long?>

    /**
     * One-shot event stream for asynchronous alerts. No replay;
     * consumers that need the last-seen alert should keep their own
     * most-recent reference.
     */
    val alerts: SharedFlow<WheelAlert>

    // ---- Unified commands (typed) --------------------------------------

    /** Dispatch any [WheelCommand]. Never throws except for local arg validation. */
    suspend fun dispatch(command: WheelCommand): CommandOutcome

    // ---- Unified commands (convenience) --------------------------------
    //
    // Every method below is a thin sugar over [dispatch] with the
    // corresponding [WheelCommand] subtype. Feature modules may pick
    // either style.

    suspend fun setHeadlight(on: Boolean): CommandOutcome =
        dispatch(WheelCommand.SetHeadlight(on))

    suspend fun setLedStrip(on: Boolean): CommandOutcome =
        dispatch(WheelCommand.SetLedStrip(on))

    suspend fun setDecorativeLights(on: Boolean): CommandOutcome =
        dispatch(WheelCommand.SetDecorativeLights(on))

    suspend fun beep(): CommandOutcome = dispatch(WheelCommand.Beep)

    suspend fun horn(): CommandOutcome = dispatch(WheelCommand.Horn)

    suspend fun setVolume(percent: Int): CommandOutcome =
        dispatch(WheelCommand.SetVolume(percent))

    suspend fun playSound(soundId: Int): CommandOutcome =
        dispatch(WheelCommand.PlaySound(soundId))

    suspend fun setMaxSpeedKmh(kmh: Float): CommandOutcome =
        dispatch(WheelCommand.SetMaxSpeedKmh(kmh))

    suspend fun setTiltbackKmh(kmh: Float): CommandOutcome =
        dispatch(WheelCommand.SetTiltbackKmh(kmh))

    suspend fun setPedalSensitivity(level: Int): CommandOutcome =
        dispatch(WheelCommand.SetPedalSensitivity(level))

    suspend fun setPedalHorizontal(angleDegrees: Float): CommandOutcome =
        dispatch(WheelCommand.SetPedalHorizontal(angleDegrees))

    suspend fun setRideMode(modeCode: Int): CommandOutcome =
        dispatch(WheelCommand.SetRideMode(modeCode))

    suspend fun calibrate(): CommandOutcome = dispatch(WheelCommand.Calibrate)

    suspend fun powerOff(): CommandOutcome = dispatch(WheelCommand.PowerOff)

    suspend fun unlockWithPin(pin: String): CommandOutcome =
        dispatch(WheelCommand.UnlockWithPin(pin))

    // ---- Teardown -------------------------------------------------------

    /**
     * Close the connection. Cancels all internal coroutines,
     * disconnects the transport, and transitions [state] to
     * [ConnectionState.Disconnected]. Idempotent.
     */
    suspend fun close()
}
