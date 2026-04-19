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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val alerts: SharedFlow<WheelAlert> = _alerts.asSharedFlow()

    // ---- Derived single-field flows ------------------------------------

    override val speedKmh: StateFlow<Float?> =
        _telemetry.map { it.speedKmh }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    override val voltageV: StateFlow<Float?> =
        _telemetry.map { it.voltageV }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    override val batteryPercent: StateFlow<Float?> =
        _telemetry.map { it.batteryPercent }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    override val currentA: StateFlow<Float?> =
        _telemetry.map { it.currentA }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    override val mosTemperatureC: StateFlow<Float?> =
        _telemetry.map { it.mosTemperatureC }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    override val totalDistanceMetres: StateFlow<Long?> =
        _telemetry.map { it.totalDistanceMetres }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

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

        ingestJob = scope.launch { runIngestLoop() }

        val period = codec.keepAlivePeriodMillis
        if (period > 0L) {
            keepAliveJob = scope.launch { runKeepAliveLoop(period) }
        }

        _state.value = ConnectionState.Handshaking(codec.family)
        for (frame in codec.handshakeFrames(codecState)) {
            try {
                transport.write(frame)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Best-effort: if the handshake write fails the ingest
                // loop will surface the link failure on the next cycle.
            }
        }
    }

    // ---- Ingest / keep-alive -------------------------------------------

    private suspend fun runIngestLoop() {
        try {
            transport.incoming.collect { bytes -> handleBytes(bytes) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!closed) {
                _state.value = ConnectionState.Failed(
                    ConnectionState.Failed.Reason.BLE_LINK_LOST,
                    e.message,
                )
            }
        }
    }

    private suspend fun handleBytes(bytes: ByteArray) {
        val events = codec.decode(codecState, bytes)
        for (event in events) {
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
            if (closed) return
            val frames = codec.keepAliveFrames(codecState)
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
            codec.encode(codecState, command)
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
        ingestJob?.cancel()
        keepAliveJob?.cancel()
        ingestJob = null
        keepAliveJob = null
        try {
            transport.disconnect()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Swallow: we are tearing down and the caller expects a
            // quiet disconnect.
        }
        _state.value = ConnectionState.Disconnected
    }

    // ---- Helpers -------------------------------------------------------

    /**
     * Merge [delta] into [base]: non-null fields on [delta] overwrite
     * their counterparts on [base]; fields that the codec left at
     * `null` inherit from [base]. [WheelTelemetry.faults] is replaced
     * wholesale — the codec is responsible for emitting the full
     * current fault set on every update so that transitions remain
     * observable.
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
            faults = delta.faults,
        )
}
