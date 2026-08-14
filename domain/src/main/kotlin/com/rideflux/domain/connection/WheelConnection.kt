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
 * **Staleness caveat:** [StateFlow] retains its last value even after
 * the scope is cancelled. Reading `telemetry.value` (or the derived
 * single-field flows) after [state] becomes `Failed`/`Disconnected`
 * still returns the last snapshot as if the wheel were live. Combine
 * those flows with [state] (e.g. `state.filter { it == Ready }
 * .flatMapLatest { telemetry }`) when a stale read would be unsafe,
 * especially after a terminal failure.
 *
 * ### Command semantics
 *
 * Commands are dispatched via [dispatch] (for the full typed sealed
 * class) or the convenience `suspend fun`s below (for the common
 * cases). Both paths return a [CommandOutcome]; domain-level validation
 * failures surface as `IllegalArgumentException`, cancellation is
 * always propagated, and IO failures surface via
 * [CommandOutcome.TransportError] / [CommandOutcome.InvalidArgument].
 * A `Success` outcome means the bytes reached the BLE stack, **not**
 * that the device acted on them — confirm by observing [telemetry].
 *
 * ### Dispatch vs. teardown
 *
 * [dispatch] and [close] are coordinated by the implementation: a
 * command dispatched after [close] is rejected with a failure outcome,
 * and an in-flight command either completes or is cancelled during
 * teardown. Feature modules should not rely on `dispatch` succeeding
 * concurrently with `close`.
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
     * One-shot event stream for asynchronous alerts. This includes
     * safety-relevant events (tilt-back, speed cutoff, over-temp,
     * fall detection), so at minimum the latest alert is replayed to
     * late subscribers. Implementations must not silently drop alerts
     * for an active collector; use a buffered SharedFlow or backpressure.
     */
    val alerts: SharedFlow<WheelAlert>

    // ---- Unified commands (typed) --------------------------------------

    /**
     * Dispatch any [WheelCommand]. Domain-level validation failures
     * (out-of-range parameters) surface as [CommandOutcome.InvalidArgument]
     * outcomes where the implementation models them, or as
     * [IllegalArgumentException] for locally-validated parameters.
     * [kotlinx.coroutines.CancellationException] is always propagated
     * (structured concurrency); transport failures surface as
     * [CommandOutcome.TransportError]. Consumers should therefore not
     * catch broadly.
     */
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

    suspend fun setVolume(percent: Int): CommandOutcome {
        require(percent in 0..100) { "volume must be 0..100, got $percent" }
        return dispatch(WheelCommand.SetVolume(percent))
    }

    suspend fun playSound(soundId: Int): CommandOutcome {
        require(soundId in 0..255) { "soundId must be a byte, got $soundId" }
        return dispatch(WheelCommand.PlaySound(soundId))
    }

    suspend fun setMaxSpeedKmh(kmh: Float): CommandOutcome {
        require(kmh.isFinite() && kmh >= 0f) { "kmh must be finite and non-negative, got $kmh" }
        return dispatch(WheelCommand.SetMaxSpeedKmh(kmh))
    }

    suspend fun setTiltbackKmh(kmh: Float): CommandOutcome {
        require(kmh.isFinite() && kmh >= 0f) { "kmh must be finite and non-negative, got $kmh" }
        return dispatch(WheelCommand.SetTiltbackKmh(kmh))
    }

    suspend fun setPedalSensitivity(level: Int): CommandOutcome {
        require(level >= 0) { "pedal sensitivity must be non-negative, got $level" }
        return dispatch(WheelCommand.SetPedalSensitivity(level))
    }

    suspend fun setPedalHorizontal(angleDegrees: Float): CommandOutcome {
        require(angleDegrees.isFinite()) { "angle must be finite, got $angleDegrees" }
        return dispatch(WheelCommand.SetPedalHorizontal(angleDegrees))
    }

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
