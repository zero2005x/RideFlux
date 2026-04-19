/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.telemetry.RideMode
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * UI projection of a single wheel's live state.
 *
 * Separate fields instead of exposing `WheelTelemetry` directly keep
 * the Compose screen decoupled from the domain package and make each
 * tile trivially recomposable via `collectAsState()`.
 */
data class DashboardUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val identity: WheelIdentity? = null,
    val speedKmh: Float? = null,
    val voltageV: Float? = null,
    val batteryPercent: Float? = null,
    val currentA: Float? = null,
    val mosTemperatureC: Float? = null,
    val totalDistanceMetres: Long? = null,
    val rideMode: RideMode? = null,
    val headlightOn: Boolean = false,
)

/**
 * ViewModel that owns the [WheelConnection] for one MAC address and
 * projects its reactive surface onto a pair of flows consumed by the
 * dashboard Compose screen:
 *
 *  * [uiState] — snapshot telemetry and connection lifecycle merged
 *    into one `StateFlow` ready for `collectAsState()`.
 *  * [alerts] — one-shot discrete alert events, replayed as a
 *    `SharedFlow` with a small buffer so the UI never misses a
 *    transition while composing.
 *
 * The connection handle is obtained lazily on first access via a
 * [Deferred] so that constructor injection stays cheap (Hilt
 * constructs ViewModels on the main thread); the underlying
 * `connect()` call ultimately runs on [Dispatchers.IO].
 *
 * ### MAC address resolution
 * The target address is read from [SavedStateHandle] under the key
 * [ARG_ADDRESS]. Navigation code places it there via
 * `navArgument(ARG_ADDRESS) { type = NavType.StringType }`. The
 * optional [ARG_FAMILY] key, when present, forwards an explicit
 * family hint to
 * [WheelRepository.connect] — otherwise the repository falls back
 * to its own UUID-based inference.
 *
 * ### Lifecycle / ref-counting
 * On [onCleared] the ViewModel calls
 * [WheelConnection.close] exactly once, honouring the repository's
 * ref-count so that other observers of the same address stay
 * connected.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel @Inject constructor(
    private val wheelRepository: WheelRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** MAC of the target device (e.g. `"AA:BB:CC:DD:EE:FF"`). Exposed so nav callers can re-route to the HUD. */
    val address: String = requireNotNull(savedStateHandle[ARG_ADDRESS]) {
        "DashboardViewModel requires SavedStateHandle[$ARG_ADDRESS]"
    }

    /** Optional family hint — `null` means "let the repository decide". */
    val expectedFamily: WheelFamily? =
        (savedStateHandle.get<String>(ARG_FAMILY))?.let {
            runCatching { WheelFamily.valueOf(it) }.getOrNull()
        }

    /**
     * Connect exactly once. Subsequent property reads await the same
     * [Deferred] without racing another [WheelRepository.connect]
     * call.
     */
    private val connectionAsync: Deferred<WheelConnection> = viewModelScope.async {
        withContext(Dispatchers.IO) {
            wheelRepository.connect(address = address, expectedFamily = expectedFamily)
        }
    }

    /**
     * Locally-tracked headlight state. The wheel telemetry does not
     * report headlight state back, so we drive this flag from the
     * user's own toggle via [setHeadlight] and surface it on
     * [DashboardUiState.headlightOn] for UI feedback.
     */
    private val headlightOnFlow = MutableStateFlow(false)

    /**
     * Unified UI state. We `flatMapLatest` off a flow that emits the
     * resolved [WheelConnection] exactly once, which flips every
     * derived state flow to the live values.
     */
    val uiState: StateFlow<DashboardUiState> = flow {
        emit(connectionAsync.await())
    }.flatMapLatest { conn ->
        combineDashboardFlows(conn)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
        initialValue = DashboardUiState(),
    )

    /**
     * One-shot alert stream forwarded from the active connection.
     * Uses a [MutableSharedFlow] with extra buffer so alerts raised
     * during configuration changes are not lost.
     */
    private val _alerts = MutableSharedFlow<WheelAlert>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val alerts: SharedFlow<WheelAlert> = _alerts.asSharedFlow()

    init {
        viewModelScope.launch {
            val conn = connectionAsync.await()
            conn.alerts.onEach { _alerts.emit(it) }.launchIn(viewModelScope)
        }
    }

    /**
     * Dispatch a typed [WheelCommand] and ignore the outcome — the
     * Compose layer observes [uiState] to see the effect take hold.
     * Returns the [CommandOutcome] so unit tests and advanced call
     * sites can assert / surface it if they wish.
     */
    suspend fun dispatch(command: WheelCommand): CommandOutcome {
        val conn = connectionAsync.await()
        return conn.dispatch(command)
    }

    // ---- High-level command helpers -----------------------------------

    /**
     * Toggle the wheel's primary headlight. Optimistically updates
     * [DashboardUiState.headlightOn] first so the UI feels immediate;
     * on transport failure the flag stays on the last known value
     * (the wheel will correct the rider by not lighting up).
     */
    fun setHeadlight(on: Boolean) {
        headlightOnFlow.value = on
        viewModelScope.launch {
            dispatch(WheelCommand.SetHeadlight(on))
        }
    }

    /**
     * Select a pedals ride mode. Pass the family-specific integer
     * code (e.g. `0 = Soft`, `1 = Medium`, `2 = Hard` for most
     * families). The actual applied mode is confirmed by the next
     * telemetry frame, which updates [DashboardUiState.rideMode].
     */
    fun setPedalsMode(modeCode: Int) {
        viewModelScope.launch {
            dispatch(WheelCommand.SetRideMode(modeCode))
        }
    }

    /** Fire a single short beep on the wheel's speaker. */
    fun beep() {
        viewModelScope.launch {
            dispatch(WheelCommand.Beep)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Fire-and-forget; the repository ref-count teardown must run
        // even if viewModelScope is already cancelled.
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            try {
                connectionAsync.await().close()
            } catch (_: Throwable) {
                // Best-effort; connection may already be closed.
            }
        }
    }

    // ---- Helpers -------------------------------------------------------

    private fun combineDashboardFlows(conn: WheelConnection) = with(conn) {
        val baseFlow = kotlinx.coroutines.flow.combine(
            state,
            identity,
            speedKmh,
            voltageV,
            batteryPercent,
        ) { s, id, spd, v, bp -> Tuple5(s, id, spd, v, bp) }

        val telemetryFlow = kotlinx.coroutines.flow.combine(
            baseFlow,
            currentA,
            mosTemperatureC,
            totalDistanceMetres,
            telemetry.map { it.rideMode },
        ) { b, cur, mos, odo, mode ->
            DashboardUiState(
                connectionState = b.state,
                identity = b.identity,
                // Speed can be reported as a signed scalar by some
                // codecs (e.g. during reverse roll). Riders read the
                // dashboard for magnitude, not direction, so we take
                // the absolute value once, here, so every consumer
                // (phone dashboard, HUD) stays in sync.
                speedKmh = b.speedKmh?.let { kotlin.math.abs(it) },
                voltageV = b.voltageV,
                // If we're connected but have not yet seen a battery
                // frame, show 0% instead of a silent `null` so the
                // gauge has something to render. Pre-Ready we leave
                // `null` so the UI can still show the loading state.
                batteryPercent = b.batteryPercent
                    ?: if (b.state == ConnectionState.Ready) 0f else null,
                currentA = cur,
                mosTemperatureC = mos,
                totalDistanceMetres = odo,
                rideMode = mode,
            )
        }

        kotlinx.coroutines.flow.combine(
            telemetryFlow,
            headlightOnFlow,
        ) { base, headlight -> base.copy(headlightOn = headlight) }
    }

    private data class Tuple5(
        val state: ConnectionState,
        val identity: WheelIdentity?,
        val speedKmh: Float?,
        val voltageV: Float?,
        val batteryPercent: Float?,
    )

    companion object {
        const val ARG_ADDRESS: String = "address"
        const val ARG_FAMILY: String = "family"
    }
}
