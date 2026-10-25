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
import com.rideflux.domain.command.supports
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.connection.WheelSessionSnapshot
import com.rideflux.domain.safety.DangerTier
import com.rideflux.domain.safety.MotionInterlock
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.telemetry.WheelTelemetryMerge
import com.rideflux.domain.transport.BleTransport
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity
import com.rideflux.domain.wheel.WheelSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
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

private const val HANDSHAKE_TIMEOUT_MILLIS = 15_000L

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
    codec: WheelCodec?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * When set, the codec is built from the discovery resolution instead of
     * the provisional `codec` argument. This is what makes the discovered
     * GATT table, rather than the pre-connect guess, decide the family.
     */
    private val codecForResolution: ((WheelResolution) -> WheelCodec)? = null,
) : WheelConnection {

    /**
     * The one immutable binding of a live session: codec, its state, the
     * family and the discovery resolution that produced it.
     *
     * Read as a single object by `dispatch`, the ingest loop and the
     * keep-alive loop, so they can never mix a codec from one session with the
     * state of another. Only the *final* binding is ever published: the
     * provisional codec passed to the constructor is stored in
     * [initialCodec] and is never exposed as a session binding.
     */
    private class SessionBinding(
        val epoch: Long,
        val codec: WheelCodec,
        val codecState: WheelCodec.State,
        val family: WheelFamily,
        val resolution: WheelResolution?,
    )

    /** Pre-connect codec, used only when no discovery resolution exists. */
    private val initialCodec: WheelCodec? = codec

    @Volatile
    private var binding: SessionBinding? = null

    /** Family bound to the live session; `null` before binding and after close. */
    val resolvedFamily: WheelFamily?
        get() = binding?.family

    /** Discovery resolution of the live session; `null` when none was used. */
    val resolution: WheelResolution?
        get() = binding?.resolution

    /**
     * Codec bound to the live session.
     *
     * Throws before the final binding exists and after close: unlike the old
     * field, this can never hand out the provisional codec to a caller that
     * is about to encode a command for the wrong family.
     */
    val codec: WheelCodec
        get() = binding?.codec ?: error("codec is not resolved yet")

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
    private val motionInterlock = MotionInterlock()

    // ---- Backing flows -------------------------------------------------

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _identity = MutableStateFlow<WheelIdentity?>(null)
    override val identity: StateFlow<WheelIdentity?> = _identity.asStateFlow()

    private val _capabilities = MutableStateFlow<WheelCapabilities?>(null)
    override val capabilities: StateFlow<WheelCapabilities?> = _capabilities.asStateFlow()

    private val _support = MutableStateFlow<WheelSupport?>(null)
    override val support: StateFlow<WheelSupport?> = _support.asStateFlow()

    private val _sessionSnapshot = MutableStateFlow<WheelSessionSnapshot?>(null)
    override val sessionSnapshot: StateFlow<WheelSessionSnapshot?> =
        _sessionSnapshot.asStateFlow()
    private var snapshotSequence = 0L

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
    private var handshakeTimeoutJob: Job? = null
    private var started: Boolean = false
    @Volatile private var closed: Boolean = false

    /**
     * Monotonic session epoch, mutated under [lifecycleMutex].
     *
     * [start] takes a fresh epoch; [close] increments it again to retire any
     * start that is still in flight. A binding (or a state write) is published
     * only while its epoch is current, so a codec factory that finishes after
     * close cannot republish a retired session.
     */
    @Volatile private var sessionEpoch: Long = 0

    /**
     * Set once the codec has decoded anything usable from the wheel.
     *
     * The handshake watchdog only fires on a *silent* link. Several
     * families gate [DecodeEvent.Identified] on a complete identity
     * tuple (Inmotion I2 needs model, serial *and* firmware), so a
     * variant that never answers one of those queries streams perfectly
     * good telemetry while staying in [ConnectionState.Handshaking].
     * Tearing that link down after the timeout would break a working
     * ride; staying silent for the whole window would not.
     */
    @Volatile private var sawWheelData: Boolean = false

    /**
     * Connect the transport, start ingesting bytes and (if required)
     * the keep-alive loop, then emit the codec's handshake frames.
     *
     * Exceptions raised by the transport during connect transition
     * [state] to [ConnectionState.Failed] instead of propagating.
     * Idempotent: additional calls after the first return immediately.
     */
    suspend fun start() {
        val epoch = lifecycleMutex.withLock {
            if (started || closed) return
            started = true
            ++sessionEpoch
            // Published under the same lock that retires the session: a
            // concurrent close() either happens before this line (then we
            // return above) or after it (then it overwrites Connecting with
            // its terminal state).
            _state.value = ConnectionState.Connecting
            sessionEpoch
        }
        try {
            transport.connect()
        } catch (_: TimeoutCancellationException) {
            // The transport's own timeout, not our caller being
            // cancelled: report it as a link failure (must be caught
            // before CancellationException, which it extends) and make
            // sure the half-open GATT is released.
            publishFailure(
                ConnectionState.Failed(
                    ConnectionState.Failed.Reason.BLE_LINK_LOST,
                    "BLE connection timed out",
                ),
                epoch = epoch,
            )
            try { transport.disconnect() } catch (_: Throwable) { /* best-effort */ }
            return
        } catch (e: FamilyResolutionException) {
            // The discovered table could not select a family: fail with the
            // diagnostic and do NOT send any bootstrap for a guessed family.
            publishFailure(
                ConnectionState.Failed(
                    ConnectionState.Failed.Reason.UNKNOWN_FAMILY,
                    e.resolution.reason,
                ),
                epoch = epoch,
            )
            try { transport.disconnect() } catch (_: Throwable) { /* best-effort */ }
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            publishFailure(
                ConnectionState.Failed(
                    ConnectionState.Failed.Reason.BLE_LINK_LOST,
                    e.message,
                ),
                epoch = epoch,
            )
            return
        }

        // Bind the final codec from the discovered table. This runs before
        // any handshake frame is computed, so an unresolved or contradictory
        // table can never put another family's bootstrap on the wire. The
        // provisional codec is deliberately *not* published: until this
        // method returns a binding, dispatch() has nothing to encode with and
        // therefore cannot write another family's command.
        val bound: SessionBinding = if (codecForResolution != null) {
            val resolved = (transport as? NegotiatingTransport)?.resolution
            if (resolved == null || !resolved.usable || resolved.family == null) {
                val reason = resolved?.reason ?: "transport reported no GATT resolution"
                publishFailure(
                    ConnectionState.Failed(ConnectionState.Failed.Reason.UNKNOWN_FAMILY, reason),
                    epoch = epoch,
                )
                try { transport.disconnect() } catch (_: Throwable) { /* best-effort */ }
                return
            }
            val finalCodec = try {
                codecForResolution.invoke(resolved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A binding that cannot be built is a failed session, not a
                // half-bound one: no codec is published, so dispatch stays
                // unavailable and nothing is written.
                publishFailure(
                    ConnectionState.Failed(
                        ConnectionState.Failed.Reason.BLE_LINK_LOST,
                        "codec binding failed: ${e.message}",
                    ),
                    epoch = epoch,
                )
                try { transport.disconnect() } catch (_: Throwable) { /* best-effort */ }
                return
            }
            publishBinding(epoch, finalCodec, resolved.family, resolved) ?: return
        } else {
            val initial = initialCodec
            if (initial == null) {
                publishFailure(
                    ConnectionState.Failed(
                        ConnectionState.Failed.Reason.UNKNOWN_FAMILY,
                        "no codec was supplied and the session did not negotiate one",
                    ),
                    epoch = epoch,
                )
                try { transport.disconnect() } catch (_: Throwable) { /* best-effort */ }
                return
            }
            publishBinding(epoch, initial, initial.family, null) ?: return
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
        try {
            // A non-positive period means no timer. Do not invoke
            // keepAliveFrames here: codecs may compute those frames
            // dynamically and a transient encoder failure must be
            // contained by the timer loop rather than aborting start().
            keepAlivePeriodMillis = bound.codec.keepAlivePeriodMillis.takeIf { it > 0L }
            handshake = codecMutex.withLock { bound.codec.handshakeFrames(bound.codecState) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            try { transport.disconnect() } catch (_: Throwable) { /* best-effort */ }
            publishFailure(
                ConnectionState.Failed(
                    ConnectionState.Failed.Reason.BLE_LINK_LOST,
                    e.message,
                ),
                epoch = epoch,
            )
            return
        }

        lifecycleMutex.withLock {
            // Do not start any work for a session that close() retired while
            // the binding or its handshake frames were being built.
            if (closed || sessionEpoch != epoch) return
            _state.value = ConnectionState.Handshaking(bound.family)
            // Arm the watchdog before the ingest loop: an UNDISPATCHED
            // ingest can decode a buffered reply synchronously, and its
            // Identified branch must find a job here to cancel.
            handshakeTimeoutJob = scope.launch {
                delay(HANDSHAKE_TIMEOUT_MILLIS)
                // Only a link that never said anything is a failed
                // handshake (see [sawWheelData]). A wheel that streams
                // telemetry without completing identification keeps its
                // connection.
                if (closed || sawWheelData) return@launch
                val timedOut = publishFailure(
                    ConnectionState.Failed(ConnectionState.Failed.Reason.HANDSHAKE_TIMEOUT),
                    epoch = epoch,
                    expected = ConnectionState.Handshaking(bound.family),
                )
                if (timedOut) {
                    ingestJob?.cancel()
                    keepAliveJob?.cancel()
                    _telemetry.value = WheelTelemetry.EMPTY
                    try { transport.disconnect() } catch (_: Throwable) { /* best-effort */ }
                }
            }
            // Subscribe before writing: a wheel can reply before write()
            // returns. Publishing Handshaking first avoids the state
            // regression an early Identified would otherwise cause.
            ingestJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { runIngestLoop() }
        }
        for (frame in handshake) {
            if (closed || _state.value is ConnectionState.Failed) return
            try {
                // The epoch is re-checked while writing, so a close() that
                // starts between two frames stops the remaining ones.
                if (!writeIfCurrent(bound, frame)) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A wheel that cannot be written to will never answer.
                // Previously this was swallowed and the connection sat
                // in Handshaking until something else happened to tear
                // it down; make it terminal instead.
                if (publishFailure(
                        ConnectionState.Failed(
                            ConnectionState.Failed.Reason.BLE_LINK_LOST,
                            "Handshake write failed: ${e.message}",
                        ),
                        epoch = epoch,
                    )
                ) {
                    _telemetry.value = WheelTelemetry.EMPTY
                    motionInterlock.reset()
                }
                ingestJob?.cancel()
                try { transport.disconnect() } catch (_: Throwable) { /* best-effort */ }
                return
            }
        }

        // Atomically launch the keep-alive job. A concurrent
        // close() that wins the lock first sets closed = true and we
        // bail out without launching anything (close() already
        // disconnected the transport); a close() that loses the race
        // will cancel the jobs we just registered.
        lifecycleMutex.withLock {
            // Nothing may be launched for a retired session: close() owns the
            // terminal state and has already disconnected the transport.
            if (closed || sessionEpoch != epoch) return
            if (_state.value !is ConnectionState.Failed && keepAlivePeriodMillis != null) {
                keepAliveJob = scope.launch { runKeepAliveLoop(keepAlivePeriodMillis, bound) }
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
     * Single failure path for a lost link: retires the session and publishes
     * [ConnectionState.Failed], then stops the keep-alive loop so it does not
     * keep writing frames to a dead transport every period until close()
     * happens to be called.
     *
     * Retirement and the terminal state are one critical section inside
     * [publishFailure], so a command or poll frame can never be admitted
     * after the link was declared lost — including while the transport has
     * not finished tearing the writable link down.
     */
    private suspend fun onLinkLost(message: String?) {
        if (!publishFailure(
                ConnectionState.Failed(
                    ConnectionState.Failed.Reason.BLE_LINK_LOST,
                    message,
                ),
            )
        ) {
            return
        }
        keepAliveJob?.cancel()
        keepAliveJob = null
        handshakeTimeoutJob?.cancel()
        _telemetry.value = WheelTelemetry.EMPTY
        motionInterlock.reset()
    }

    private suspend fun handleBytes(bytes: ByteArray) {
        // decodeSafely (not decode): wire data is untrusted and a missed
        // bounds check inside a family parser must degrade to a Malformed
        // event rather than kill the ingest loop.
        // The binding is read once, so a session that is closed between these
        // lines cannot swap codec/state underneath the decoder.
        val bound = binding ?: return
        if (!isLive(bound)) return
        val events = codecMutex.withLock { bound.codec.decodeSafely(bound.codecState, bytes) }
        for (event in events) {
            // close() cancels this job but cancellation is only observed
            // at suspension points; the flow mutations below are not
            // suspending, so an in-flight handleBytes could otherwise
            // republish stale telemetry — or flip the state back to
            // Ready — after close() already reset everything.
            if (closed || _state.value is ConnectionState.Failed) return
            // Interpreted events prove the wheel is talking to the handshake
            // watchdog; uninterpreted BMS envelopes are not identification evidence.
            if (event !is DecodeEvent.Malformed && event !is DecodeEvent.RawBmsFrame) sawWheelData = true
            when (event) {
                is DecodeEvent.TelemetryUpdate -> {
                    // Only a fresh decoded speed frame counts; retained snapshot values do not.
                    event.snapshot.speedKmh?.let { motionInterlock.observe(it, clock()) }
                    _telemetry.update { current ->
                        WheelTelemetryMerge.merge(
                            base = current,
                            delta = event.snapshot,
                            invalidated = event.invalidated,
                            fallbackTimestampMillis = clock(),
                        )
                    }
                }
                is DecodeEvent.Alert -> {
                    _alerts.emit(event.alert)
                }
                is DecodeEvent.Identified -> {
                    _identity.value = event.identity
                    _capabilities.value = event.capabilities
                    _support.value = event.support
                    // One immutable publication for identity + its evidence.
                    // The legacy flows remain for compatibility, but no
                    // consumer should pair them across this boundary.
                    _sessionSnapshot.value = WheelSessionSnapshot(
                        sequence = ++snapshotSequence,
                        identity = event.identity,
                        capabilities = event.capabilities,
                        support = event.support,
                    )
                    _state.value = ConnectionState.Ready
                    handshakeTimeoutJob?.cancel()
                }
                is DecodeEvent.Malformed -> motionInterlock.reset()
                is DecodeEvent.RawBmsFrame -> {
                    // Preserve evidence without refreshing telemetry or the stationary interlock.
                    _telemetry.update { current -> current.copy(bmsFrame = event.frame) }
                }
            }
        }
    }

    private suspend fun runKeepAliveLoop(periodMillis: Long, bound: SessionBinding) {
        while (scope.isActive) {
            delay(periodMillis)
            // Bail out when the link is gone: once the ingest loop
            // detects link loss and flips state to Failed, writing
            // keep-alives to the dead transport is pointless churn.
            val st = _state.value
            if (!isLive(bound) || (st !is ConnectionState.Ready && st !is ConnectionState.Handshaking)) {
                return
            }
            // Guard the codec call like the transport write below: an
            // escaping exception would kill the keep-alive timer and,
            // depending on the scope's job type, cancel the caller's
            // scope or crash the process.
            val frames = try {
                codecMutex.withLock { bound.codec.keepAliveFrames(bound.codecState) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Swallow: the ingest loop is the single source of
                // truth for link-failure detection.
                continue
            }
            for (frame in frames) {
                try {
                    // Never write on behalf of a retired session.
                    if (!writeIfCurrent(bound, frame)) return
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
        val tier = when (command) {
            is WheelCommand.Raw -> DangerTier.FORBIDDEN
            // Remote power-off stays available, but only after three fresh stationary frames;
            // the UI adds its own confirmation dialog.
            is WheelCommand.PowerOff, is WheelCommand.Calibrate, is WheelCommand.SetMaxSpeedKmh,
            is WheelCommand.SetTiltbackKmh, is WheelCommand.SetPedalSensitivity,
            is WheelCommand.SetPedalHorizontal, is WheelCommand.SetRideMode,
            is WheelCommand.UnlockWithPin -> DangerTier.CRITICAL
            else -> DangerTier.BENIGN
        }
        try {
            motionInterlock.requireAllowed(tier, clock())
        } catch (e: SecurityException) {
            return CommandOutcome.InvalidArgument(command, e.message ?: "Safety interlock rejected command")
        }

        // Final-binding gate: the session binding is published atomically
        // after the discovery resolution produced the real codec. Before that
        // point (and after close) there is no codec to encode with, so a
        // command must stay unavailable and produce **zero** transport writes
        // — the provisional codec from the constructor must never reach the
        // wire for a family discovery has already contradicted.
        val bound = binding ?: return CommandOutcome.Unsupported(command)
        if (!isLive(bound)) return CommandOutcome.Unsupported(command)

        // Capability gate. Before identification the matrix is unknown and
        // the codec remains the authority (some families accept a command
        // during their handshake). Once identification has published a
        // matrix, a command whose flag is false must not reach the encoder:
        // this is the second line of defence behind the UI and the codec.
        _capabilities.value?.let { capabilities ->
            if (!capabilities.supports(command)) {
                return CommandOutcome.Unsupported(command)
            }
        }

        val frames = try {
            codecMutex.withLock { bound.codec.encode(bound.codecState, command) }
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
                // Recheck immediately before each physical write, including multi-frame commands.
                try {
                    motionInterlock.requireAllowed(tier, clock())
                } catch (e: SecurityException) {
                    return CommandOutcome.InvalidArgument(command, e.message ?: "Safety interlock rejected command")
                }
                // The session may have been closed while earlier frames were
                // being written; stop instead of finishing the sequence on a
                // retired transport.
                if (!writeIfCurrent(bound, frame)) return CommandOutcome.Unsupported(command)
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
            // Retire the session. A codec factory still running for this
            // connection now fails its publish check instead of reviving the
            // binding after close returns.
            ++sessionEpoch
        }
        // cancelAndJoin, not cancel: an in-flight handleBytes must be
        // fully unwound before the state/telemetry reset below, or it
        // can resume afterwards and republish stale telemetry (or flip
        // the state back to Ready) on an already-closed connection.
        // NonCancellable so a cancelled caller cannot skip the join.
        withContext(NonCancellable) {
            handshakeTimeoutJob?.cancelAndJoin()
            ingestJob?.cancelAndJoin()
            keepAliveJob?.cancelAndJoin()
        }
        ingestJob = null
        handshakeTimeoutJob = null
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
        motionInterlock.reset()
        // Each derived single-field flow has its own MutableStateFlow;
        // resetting _telemetry does not clear them, so consumers could
        // still read pre-close speedKmh/voltageV/... after Disconnected.
        derivedResets.forEach { it() }
        derivedResets.clear()
        _identity.value = null
        _capabilities.value = null
        _support.value = null
        _sessionSnapshot.value = null
        // Drop the session binding: a stale handle must not be able to decode
        // with the retired codec state, reuse the discovery resolution, or
        // report the old family to a new caller. The epoch check in
        // publishBinding already prevents an in-flight start from restoring
        // it; this clears the already-published one.
        binding = null
    }

    // ---- Helpers -------------------------------------------------------

    /**
     * Publish the final [codec] as this session's binding.
     *
     * Returns `null` when the epoch has been retired while the factory was
     * running (close() completed) — in that case nothing is published, no job
     * is started and no frame is written. This is the atomicity point that
     * keeps codec, state, family and resolution consistent, and that prevents
     * a retired session from coming back to life.
     */
    private suspend fun publishBinding(
        epoch: Long,
        codec: WheelCodec,
        family: WheelFamily,
        resolution: WheelResolution?,
    ): SessionBinding? = lifecycleMutex.withLock {
        if (closed || sessionEpoch != epoch) return null
        val published = SessionBinding(epoch, codec, codec.newState(), family, resolution)
        binding = published
        published
    }

    /**
     * Publish a terminal [failure] and retire the session.
     *
     * [ConnectionState.Failed] is terminal for this connection instance, so
     * the state write and the retirement of the session epoch happen in the
     * same [lifecycleMutex] critical section as every physical write's
     * [isLive] check. That makes "Failed" and "no longer writable" one
     * transition: after this returns, no `dispatch`, bootstrap frame or poll
     * frame can be admitted — not merely once the transport's `disconnect()`
     * eventually completes. A command that is already inside [writeIfCurrent]
     * finishes (or is cancelled) under the existing bounded contract, and the
     * next frame's check sees the retired epoch.
     *
     * Retiring the epoch also makes [publishBinding] reject a codec factory
     * that finishes after the failure, so a failed session cannot be revived
     * into a writable one.
     *
     * @param epoch when non-null, publish only while this is still the live
     *   session epoch (used by `start()` and the handshake watchdog to ignore
     *   a session that close() already retired).
     * @param expected when non-null, publish only while the current state is
     *   exactly this value (the silent-handshake watchdog must never overwrite
     *   `Ready`).
     * @return `true` when the failure was published and the session retired.
     */
    private suspend fun publishFailure(
        failure: ConnectionState.Failed,
        epoch: Long? = null,
        expected: ConnectionState? = null,
    ): Boolean = lifecycleMutex.withLock {
        if (closed) return false
        if (epoch != null && sessionEpoch != epoch) return false
        if (_state.value is ConnectionState.Failed) return false
        if (expected != null && _state.value != expected) return false
        _state.value = failure
        // Retire in the same critical section: isLive()/writeIfCurrent() and
        // publishBinding() now reject this binding, and the failed session can
        // never become writable again.
        ++sessionEpoch
        true
    }

    /**
     * Lock-free liveness check for a binding read from [binding].
     *
     * [ConnectionState.Failed] is terminal even before its epoch retirement is
     * observed, so it is checked explicitly here as well as in
     * [publishFailure]; every physical write uses [writeIfCurrent], which
     * re-validates this predicate under [lifecycleMutex] immediately before
     * handing bytes to the transport.
     *
     * Used by read-only paths (decode, keep-alive decision).
     */
    private fun isLive(bound: SessionBinding): Boolean =
        !closed &&
            _state.value !is ConnectionState.Failed &&
            sessionEpoch == bound.epoch &&
            binding === bound

    /**
     * Write [frame] only while [bound] is still the live session.
     *
     * The check and the write share [lifecycleMutex] with the `closed = true`
     * transition in [close] and with the epoch retirement in [publishFailure],
     * so a frame is either written before the session is retired or not at
     * all. In particular no physical write can happen after close() returns or
     * after a terminal failure was published, and a multi-frame sequence
     * re-checks the gate for every frame.
     */
    private suspend fun writeIfCurrent(bound: SessionBinding, frame: ByteArray): Boolean =
        lifecycleMutex.withLock {
            if (!isLive(bound)) return false
            transport.write(frame)
            true
        }
}
