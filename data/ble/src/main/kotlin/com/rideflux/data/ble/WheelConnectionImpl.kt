/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.transport.BleTransport
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Framework-free logical "glue" implementation of [WheelConnection].
 *
 * Responsibilities:
 *  * Pump raw bytes from [BleTransport.incoming] through [WheelCodec.decode].
 *  * Project [DecodeEvent.TelemetryUpdate] deltas onto a single unified
 *    [telemetry] StateFlow (non-null fields overwrite, nulls are preserved).
 *  * Fan out [DecodeEvent.Alert] events to [alerts].
 *  * Track [DecodeEvent.Identified] and flip [state] to
 *    [ConnectionState.Ready].
 *  * Run the codec's keep-alive cadence, if any.
 *  * Encode typed [WheelCommand]s via the codec and write them out.
 *
 * This class contains no Android / BLE-stack code; the concrete GATT
 * plumbing lives elsewhere (the future Kable-backed `BleTransport`
 * implementation in this same module).
 *
 * ### Lifecycle
 *
 * The caller constructs the instance, then invokes [start] exactly
 * once to kick off the ingest loop, keep-alive timer and handshake.
 * When finished, [close] cancels everything and disconnects the
 * transport. Both are idempotent.
 *
 * The [scope] is owned by the caller (typically a repository scope or
 * a test scope); this class never creates its own root job so that
 * cancellation semantics remain predictable.
 */
class WheelConnectionImpl(
    private val transport: BleTransport,
    private val codec: WheelCodec,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : WheelConnection {

    private val codecState: WheelCodec.State = codec.newState()

    /**
     * Serialises every touch of [codecState].
     *
     * [WheelCodec.State] is explicitly documented as *not* thread-safe:
     * decode mutates reassembly buffers and per-family protocol state,
     * and encode/keepAliveFrames read (and for some families mutate) the
     * same object. Without this lock the ingest loop, the keep-alive
     * timer and a caller's dispatch() run concurrently on it and can
     * corrupt frame reassembly.
     */
    private val codecMutex = Mutex()

    // ---- Backing flows -------------------------------------------------

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _identity = MutableStateFlow<WheelIdentity?>(null)
    override val identity: StateFlow<WheelIdentity?> = _identity.asStateFlow()

    private val _capabilities = MutableStateFlow<WheelCapabilities?>(null)
    override val capabilities: StateFlow<WheelCapabilities?> = _capabilities.asStateFlow()

    private val _telemetry = MutableStateFlow(WheelTelemetry.EMPTY)
    override val telemetry: StateFlow<WheelTelemetry> = _telemetry.asStateFlow()

    private val _alerts = MutableSharedFlow<WheelAlert>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val alerts: SharedFlow<WheelAlert> = _alerts.asSharedFlow()

    // ---- Derived single-field flows ------------------------------------

    // Sharing jobs backing the derived flows. stateIn(Eagerly) cannot
    // be used here: its sharing coroutines are never exposed and would
    // keep collecting _telemetry until the caller-owned scope ends —
    // six leaked coroutines per closed connection, violating the
    // contract that all flows stop once the connection terminates.
    private val derivedJobs = mutableListOf<Job>()

    // Reset actions for the backing flows of the derived single-field
    // views. Cancelling the sharing job alone leaves the last emitted
    // value readable via `.value`, which would let consumers keep
    // reading pre-close speed/battery after the connection reports
    // Disconnected — contradicting the WheelConnection contract.
    private val derivedResets = mutableListOf<() -> Unit>()

    private fun <T> derivedState(selector: (WheelTelemetry) -> T): StateFlow<T?> {
        val result = MutableStateFlow<T?>(null)
        derivedResets += { result.value = null }
        derivedJobs += scope.launch {
            _telemetry.map(selector).distinctUntilChanged().collect { result.value = it }
        }
        return result.asStateFlow()
    }

    override val speedKmh: StateFlow<Float?> = derivedState { it.speedKmh }
    override val voltageV: StateFlow<Float?> = derivedState { it.voltageV }
    override val batteryPercent: StateFlow<Float?> = derivedState { it.batteryPercent }
    override val currentA: StateFlow<Float?> = derivedState { it.currentA }
    override val mosTemperatureC: StateFlow<Float?> = derivedState { it.mosTemperatureC }
    override val totalDistanceMetres: StateFlow<Long?> = derivedState { it.totalDistanceMetres }

    // ---- Internal jobs -------------------------------------------------

    private val lifecycleMutex = Mutex()
    private var ingestJob: Job? = null
    private var keepAliveJob: Job? = null
    private var started: Boolean = false
    private var closed: Boolean = false

    /**
     * Connect the transport, start ingesting bytes and (if required)
     * the keep-alive loop, then emit the codec's handshake frames.
     *
     * Exceptions raised by the transport during connect transition
     * [state] to [ConnectionState.Failed] instead of propagating.
     * Idempotent: additional calls after the first return immediately.
     */
    suspend fun start() {
        lifecycleMutex.withLock {
            if (started || closed) return
            started = true
        }

        _state.value = ConnectionState.Connecting
        try {
            transport.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.value = ConnectionState.Failed(
                ConnectionState.Failed.Reason.BLE_LINK_LOST,
                e.message,
            )
            return
        }

        // Compute all codec metadata BEFORE launching any job so that a
        // throwing codec call cannot leave ingest/keep-alive half-open.
        // The calls are guarded: the WheelCodec interface makes no
        // must-not-throw guarantee, and an exception here would
        // otherwise propagate out of start() leaving the transport
        // connected, state stuck at Connecting, and started = true
        // permanently blocking a retry.
        val keepAlivePeriodMillis: Long?
        val handshake: List<ByteArray>
        val family: com.rideflux.domain.wheel.WheelFamily
        try {
            // A non-positive period means no timer. Do not invoke
            // keepAliveFrames here: codecs may compute those frames
            // dynamically and a transient encoder failure must be
            // contained by the timer loop rather than aborting start().
            keepAlivePeriodMillis = codec.keepAlivePeriodMillis.takeIf { it > 0L }
            handshake = codecMutex.withLock { codec.handshakeFrames(codecState) }
            family = codec.family
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            try { transport.disconnect() } catch (_: Throwable) { /* best-effort */ }
            _state.value = ConnectionState.Failed(
                ConnectionState.Failed.Reason.BLE_LINK_LOST,
                e.message,
            )
            return
        }

        // Emit Handshaking and write the handshake frames BEFORE
        // launching the ingest loop: for families that auto-advertise,
        // an early Identified would otherwise flip the state to Ready
        // only to be overwritten by Handshaking (state regression), and
        // telemetry could be processed before the handshake is issued.
        _state.value = ConnectionState.Handshaking(family)
        for (frame in handshake) {
            try {
                transport.write(frame)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Best-effort: if the handshake write fails the ingest
                // loop will surface the link failure on the next cycle.
            }
        }

        // Atomically launch the ingest / keep-alive jobs. A concurrent
        // close() that wins the lock first sets closed = true and we
        // bail out without launching anything (close() already
        // disconnected the transport); a close() that loses the race
        // will cancel the jobs we just registered.
        lifecycleMutex.withLock {
            if (closed) {
                // A close() that won the race already disconnected the
                // transport and published Disconnected, but this
                // coroutine has since overwritten _state with
                // Handshaking. Restore the terminal state so a closed
                // connection is never reported as Handshaking/Failed.
                _state.value = ConnectionState.Disconnected
                return
            }
            ingestJob = scope.launch { runIngestLoop() }
            if (keepAlivePeriodMillis != null) {
                keepAliveJob = scope.launch { runKeepAliveLoop(keepAlivePeriodMillis) }
            }
        }
    }

    // ---- Ingest / keep-alive -------------------------------------------

    private suspend fun runIngestLoop() {
        try {
            transport.incoming.collect { bytes -> handleBytes(bytes) }
            // The incoming stream ended without throwing (e.g. the GATT
            // session finished cleanly): treat it as link loss so
            // subscribers learn the connection is gone.
            if (!closed) {
                onLinkLost("incoming stream ended")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!closed) {
                onLinkLost(e.message)
            }
        }
    }

    /**
     * Single failure path for a lost link: flips [state] to Failed and
     * stops the keep-alive loop so it does not keep writing frames to a
     * dead transport every period until close() happens to be called.
     */
    private fun onLinkLost(message: String?) {
        _state.value = ConnectionState.Failed(
            ConnectionState.Failed.Reason.BLE_LINK_LOST,
            message,
        )
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    private suspend fun handleBytes(bytes: ByteArray) {
        // decodeSafely (not decode): wire data is untrusted and a missed
        // bounds check inside a family parser must degrade to a Malformed
        // event rather than kill the ingest loop.
        val events = codecMutex.withLock { codec.decodeSafely(codecState, bytes) }
        for (event in events) {
            // close() cancels this job but cancellation is only observed
            // at suspension points; the flow mutations below are not
            // suspending, so an in-flight handleBytes could otherwise
            // republish stale telemetry — or flip the state back to
            // Ready — after close() already reset everything.
            if (closed) return
            when (event) {
                is DecodeEvent.TelemetryUpdate -> {
                    _telemetry.update { current -> merge(current, event.snapshot) }
                }
                is DecodeEvent.Alert -> {
                    _alerts.emit(event.alert)
                }
                is DecodeEvent.Identified -> {
                    _identity.value = event.identity
                    _capabilities.value = event.capabilities
                    _state.value = ConnectionState.Ready
                }
                is DecodeEvent.Malformed -> Unit // discard; diagnostics elsewhere
            }
        }
    }

    private suspend fun runKeepAliveLoop(periodMillis: Long) {
        while (scope.isActive) {
            delay(periodMillis)
            // Bail out when the link is gone: once the ingest loop
            // detects link loss and flips state to Failed, writing
            // keep-alives to the dead transport is pointless churn.
            val st = _state.value
            if (closed || (st !is ConnectionState.Ready && st !is ConnectionState.Handshaking)) return
            // Guard the codec call like the transport write below: an
            // escaping exception would kill the keep-alive timer and,
            // depending on the scope's job type, cancel the caller's
            // scope or crash the process.
            val frames = try {
                codecMutex.withLock { codec.keepAliveFrames(codecState) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Swallow: the ingest loop is the single source of
                // truth for link-failure detection.
                continue
            }
            for (frame in frames) {
                try {
                    transport.write(frame)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Swallow: the ingest loop is the single source of
                    // truth for link-failure detection.
                }
            }
        }
    }

    // ---- Commands ------------------------------------------------------

    override suspend fun dispatch(command: WheelCommand): CommandOutcome {
        val frames = try {
            codecMutex.withLock { codec.encode(codecState, command) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            return CommandOutcome.InvalidArgument(command, e.message ?: "invalid argument")
        } catch (e: Throwable) {
            return CommandOutcome.TransportError(command, e)
        }

        if (frames.isEmpty()) {
            return CommandOutcome.Unsupported(command)
        }

        return try {
            for (frame in frames) {
                transport.write(frame)
            }
            CommandOutcome.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            CommandOutcome.TransportError(command, e)
        }
    }

    // ---- Teardown ------------------------------------------------------

    override suspend fun close() {
        lifecycleMutex.withLock {
            if (closed) return
            closed = true
        }
        // cancelAndJoin, not cancel: an in-flight handleBytes must be
        // fully unwound before the state/telemetry reset below, or it
        // can resume afterwards and republish stale telemetry (or flip
        // the state back to Ready) on an already-closed connection.
        // NonCancellable so a cancelled caller cannot skip the join.
        withContext(NonCancellable) {
            ingestJob?.cancelAndJoin()
            keepAliveJob?.cancelAndJoin()
        }
        ingestJob = null
        keepAliveJob = null
        // Stop the derived-flow sharing coroutines so nothing keeps
        // collecting _telemetry after teardown (the WheelConnection
        // contract requires all flows to stop once the connection
        // terminates).
        derivedJobs.forEach { it.cancel() }
        derivedJobs.clear()
        try {
            // NonCancellable: if the caller's coroutine is cancelled
            // mid-teardown, a CancellationException from disconnect()
            // must not abort close() before the state/telemetry reset
            // below — closed = true is already set, so a retried
            // close() would return immediately and the connection
            // would never report Disconnected.
            withContext(NonCancellable) {
                transport.disconnect()
            }
        } catch (_: Throwable) {
            // Swallow: we are tearing down and the caller expects a
            // quiet disconnect.
        }
        _state.value = ConnectionState.Disconnected
        // Reset the telemetry snapshot so consumers cannot keep reading
        // stale speed/battery as if the wheel were still live after
        // teardown (StateFlow retains its last value otherwise).
        _telemetry.value = WheelTelemetry.EMPTY
        // Each derived single-field flow has its own MutableStateFlow;
        // resetting _telemetry does not clear them, so consumers could
        // still read pre-close speedKmh/voltageV/... after Disconnected.
        derivedResets.forEach { it() }
        derivedResets.clear()
        _identity.value = null
        _capabilities.value = null
    }

    // ---- Helpers -------------------------------------------------------

    /**
     * Merge [delta] into [base]: non-null fields on [delta] overwrite
     * their counterparts on [base]; fields that the codec left at
     * `null` inherit from [base]. [WheelTelemetry.faults] follows the
     * same null-means-unchanged rule: a delta that omits the fault set
     * preserves the previous one, and a codec that wants to report
     * "no faults" must emit an explicit empty set.
     */
    private fun merge(base: WheelTelemetry, delta: WheelTelemetry): WheelTelemetry =
        base.copy(
            timestampMillis = maxOf(base.timestampMillis, delta.timestampMillis)
                .takeIf { it > 0L } ?: clock(),
            speedKmh = delta.speedKmh ?: base.speedKmh,
            tripDistanceMetres = delta.tripDistanceMetres ?: base.tripDistanceMetres,
            totalDistanceMetres = delta.totalDistanceMetres ?: base.totalDistanceMetres,
            pitchAngleDegrees = delta.pitchAngleDegrees ?: base.pitchAngleDegrees,
            rollAngleDegrees = delta.rollAngleDegrees ?: base.rollAngleDegrees,
            voltageV = delta.voltageV ?: base.voltageV,
            currentA = delta.currentA ?: base.currentA,
            phaseCurrentA = delta.phaseCurrentA ?: base.phaseCurrentA,
            pwmPercent = delta.pwmPercent ?: base.pwmPercent,
            batteryPercent = delta.batteryPercent ?: base.batteryPercent,
            batteryVoltageV = delta.batteryVoltageV ?: base.batteryVoltageV,
            chargingState = delta.chargingState ?: base.chargingState,
            mosTemperatureC = delta.mosTemperatureC ?: base.mosTemperatureC,
            motorTemperatureC = delta.motorTemperatureC ?: base.motorTemperatureC,
            boardTemperatureC = delta.boardTemperatureC ?: base.boardTemperatureC,
            batteryTemperatureC = delta.batteryTemperatureC ?: base.batteryTemperatureC,
            imuTemperatureC = delta.imuTemperatureC ?: base.imuTemperatureC,
            rideMode = delta.rideMode ?: base.rideMode,
            workMode = delta.workMode ?: base.workMode,
            faults = delta.faults ?: base.faults,
        )
}
