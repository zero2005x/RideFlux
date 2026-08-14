/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard

import android.util.Log
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rideflux.domain.command.CommandOutcome
import com.rideflux.app.recording.RecordingService
import com.rideflux.domain.alert.ThresholdAlert
import com.rideflux.domain.alert.ThresholdMonitor
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.settings.SettingsRepository
import com.rideflux.domain.telemetry.RideMode
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    val batteryVoltageV: Float? = null,
    val currentA: Float? = null,
    val phaseCurrentA: Float? = null,
    val pwmPercent: Float? = null,
    val mosTemperatureC: Float? = null,
    val motorTemperatureC: Float? = null,
    val boardTemperatureC: Float? = null,
    val batteryTemperatureC: Float? = null,
    val totalDistanceMetres: Long? = null,
    val tripDistanceMetres: Int? = null,
    val rideMode: RideMode? = null,
    val headlightOn: Boolean = false,
    /** Faults reported by the latest [com.rideflux.domain.telemetry.WheelTelemetry] frame. */
    val faults: Set<com.rideflux.domain.telemetry.WheelFault>? = null,
    // ---- Derived ride metrics, accumulated locally --------------------
    /** Maximum speed observed since this connection went Ready, km/h. */
    val maxSpeedKmh: Float? = null,
    /** Time-weighted average speed since first non-zero sample, km/h. */
    val avgSpeedKmh: Float? = null,
    /** Wall-clock seconds since the wheel first reported speed > 1 km/h. */
    val rideTimeSeconds: Long = 0L,
    val useMetric: Boolean = true,
    val keepScreenOnDashboard: Boolean = true,
) {
    /** Instantaneous power draw in watts, derived as `V·I`. */
    val powerW: Float? get() {
        val v = voltageV ?: return null
        val i = currentA ?: return null
        return v * i
    }
}

/**
 * Single sample retained in the rolling history buffer that backs
 * the graph page. Kept deliberately small (only the values the chart
 * actually plots) so we can hold several minutes of data without
 * pressuring memory.
 */
data class TelemetrySample(
    val timestampMillis: Long,
    val speedKmh: Float?,
    val voltageV: Float?,
    val currentA: Float?,
    val batteryPercent: Float?,
    val mosTemperatureC: Float?,
    val powerW: Float?,
)

/**
 * Discrete alert with a wall-clock stamp. Used by the events page
 * to render an immutable scroll-back log.
 */
sealed interface DashboardAlert {
    data class Wheel(val value: WheelAlert) : DashboardAlert
    data class Threshold(val value: ThresholdAlert) : DashboardAlert
}

val DashboardUiState.speedUnit: String get() = if (useMetric) "km/h" else "mph"
val DashboardUiState.distanceUnit: String get() = if (useMetric) "km" else "mi"
fun DashboardUiState.displaySpeed(kmh: Float?): Float? =
    kmh?.let { if (useMetric) it else it * 0.6213712f }
fun DashboardUiState.displayDistance(metres: Number?): Double? =
    metres?.toDouble()?.let { if (useMetric) it / 1_000.0 else it / 1_609.344 }

data class TimedAlert(val timestampMillis: Long, val alert: DashboardAlert)

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
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val appContext: Context,
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
     * call. Runs under [NonCancellable] so the connection is not lost
     * if the ViewModel is cleared while the connect is in flight —
     * [onCleared] can still await the result and close the handle.
     */
    private val connectionAsync: Deferred<WheelConnection> =
        viewModelScope.async(NonCancellable + Dispatchers.IO) {
            wheelRepository.connect(address = address, expectedFamily = expectedFamily)
                .also { resolvedConnection = it }
        }

    /**
     * Resolved connection, captured as soon as [connectionAsync]
     * completes. Kept separately so [onCleared] can always release
     * the ref-counted handle even when `viewModelScope` (and the
     * deferred with it) has already been cancelled — awaiting a
     * cancelled deferred would throw and skip the teardown.
     */
    @Volatile
    private var resolvedConnection: WheelConnection? = null

    init {
        connectionAsync.invokeOnCompletion { cause ->
            if (cause == null) {
                resolvedConnection = connectionAsync.getCompleted()
            }
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
        try {
            emit(connectionAsync.await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Connect failed: keep the UI at Disconnected instead of
            // crashing the Compose collector. The error is logged in
            // the init collector above.
            Log.e(TAG, "uiState connect failed for $address", e)
        }
    }.flatMapLatest { conn ->
        combineDashboardFlows(conn)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
        initialValue = DashboardUiState(),
    )

    /**
     * Alert currently shown by the dashboard. Keeping this in the
     * ViewModel preserves the banner and its TTL across configuration
     * changes and brief periods with no UI collector.
     */
    private val _activeAlert = MutableStateFlow<DashboardAlert?>(null)
    val activeAlert: StateFlow<DashboardAlert?> = _activeAlert.asStateFlow()
    private var alertClearJob: Job? = null

    /**
     * Append-only log of the last [ALERT_LOG_LIMIT] alerts, surfaced
     * on the Events page. Newest first.
     */
    private val _alertLog = MutableStateFlow<List<TimedAlert>>(emptyList())
    val alertLog: StateFlow<List<TimedAlert>> = _alertLog.asStateFlow()

    /**
     * Rolling history of telemetry samples used by the graph page.
     * Capped at [HISTORY_LIMIT] points (~5 min @ 1 Hz). Newest last
     * so the chart can render left-to-right time order trivially.
     */
    private val _history = MutableStateFlow<List<TelemetrySample>>(emptyList())
    val history: StateFlow<List<TelemetrySample>> = _history.asStateFlow()

    // ---- Locally accumulated ride metrics -----------------------------
    private val maxSpeedFlow = MutableStateFlow<Float?>(null)
    private val avgSpeedFlow = MutableStateFlow<Float?>(null)
    private val rideTimeFlow = MutableStateFlow(0L)
    private var avgSpeedLastSampleMillis: Long? = null
    private var avgSpeedTimeWeightedSum: Double = 0.0
    private var avgSpeedTimeWeight: Long = 0L
    private var rideStartedAtMillis: Long? = null

    init {
        viewModelScope.launch {
            // Guard the connect: WheelRepository.connect can throw
            // (device not found, permission denied, BLE stack failure).
            // Without a catch the exception crashes the collector and
            // propagates out of the uiState upstream into Compose.
            val conn = try {
                connectionAsync.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "connect failed for $address", e)
                return@launch
            }
            conn.alerts.onEach { alert ->
                publishAlert(DashboardAlert.Wheel(alert))
            }.launchIn(viewModelScope)

            val thresholdFlow = settingsRepository.settings
                .map { it.alertThresholds }
                .stateIn(viewModelScope, SharingStarted.Eagerly, AlertThresholds())
            val thresholdMonitor = ThresholdMonitor(
                telemetry = conn.telemetry,
                thresholds = thresholdFlow,
                scope = viewModelScope,
            )
            thresholdMonitor.alerts.onEach { alert ->
                publishAlert(DashboardAlert.Threshold(alert))
            }.launchIn(viewModelScope)

            // Sample the merged telemetry into the history buffer.
            // We let combineDashboardFlows do the heavy lifting and
            // simply tap its output stream so the buffer always
            // matches what the UI sees.
            conn.telemetry.onEach { t ->
                val sampleMillis = if (t.timestampMillis > 0L) t.timestampMillis
                    else System.currentTimeMillis()
                accumulateRideMetrics(t.speedKmh, sampleMillis)
                val sample = TelemetrySample(
                    timestampMillis = sampleMillis,
                    speedKmh = t.speedKmh?.let { kotlin.math.abs(it) },
                    voltageV = t.voltageV,
                    currentA = t.currentA,
                    batteryPercent = t.batteryPercent,
                    mosTemperatureC = t.mosTemperatureC,
                    powerW = t.voltageV?.let { v ->
                        t.currentA?.let { i -> v * i }
                    },
                )
                _history.update { (it + sample).takeLast(HISTORY_LIMIT) }
                if (
                    conn.state.value == ConnectionState.Ready &&
                    (t.speedKmh?.let { kotlin.math.abs(it) } ?: 0f) > RIDE_START_THRESHOLD_KMH &&
                    !RecordingService.state.value.isRecording
                ) {
                    RecordingService.start(appContext, address, expectedFamily)
                }
            }.launchIn(viewModelScope)
        }
    }

    private fun publishAlert(alert: DashboardAlert) {
        _activeAlert.value = alert
        alertClearJob?.cancel()
        alertClearJob = viewModelScope.launch {
            delay(ALERT_TTL_MILLIS)
            _activeAlert.value = null
        }
        val timed = TimedAlert(System.currentTimeMillis(), alert)
        _alertLog.update { existing -> (listOf(timed) + existing).take(ALERT_LOG_LIMIT) }
    }

    /**
     * Update [maxSpeedFlow] / [avgSpeedFlow] / [rideTimeFlow] off
     * each new speed sample. The ride timer starts on the first
     * sample with magnitude > 1 km/h to avoid the clock running
     * while the wheel is parked.
     *
     * The average is time-weighted: each sample contributes its speed
     * scaled by the interval since the previous sample, so stationary
     * time (sub-threshold samples) drags the average down as the KDoc
     * for [DashboardUiState.avgSpeedKmh] promises.
     */
    private fun accumulateRideMetrics(speedRaw: Float?, sampleMillis: Long) {
        val speed = speedRaw?.takeIf { it.isFinite() }?.let { kotlin.math.abs(it) } ?: 0f
        val current = maxSpeedFlow.value ?: 0f
        if (speed > current) maxSpeedFlow.value = speed

        val now = System.currentTimeMillis()
        if (rideStartedAtMillis == null) {
            if (speed > RIDE_START_THRESHOLD_KMH) {
                rideStartedAtMillis = now
                // Anchor the average at the first riding sample. A
                // parked sample before this point must not contribute a
                // zero-speed interval to the ride average.
                avgSpeedLastSampleMillis = sampleMillis
            }
        } else {
            // Time-weighted accumulation after riding has started, so
            // later stationary periods still pull the average down.
            val prev = avgSpeedLastSampleMillis
            if (prev != null) {
                val dt = (sampleMillis - prev).coerceAtLeast(0L)
                if (dt > 0) {
                    avgSpeedTimeWeightedSum += speed.toDouble() * dt
                    avgSpeedTimeWeight += dt
                    avgSpeedFlow.value =
                        (avgSpeedTimeWeightedSum / avgSpeedTimeWeight).toFloat()
                }
            }
            avgSpeedLastSampleMillis = sampleMillis
        }

        rideStartedAtMillis?.let { start ->
            rideTimeFlow.value = (now - start) / 1_000L
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
        dispatchSafely(WheelCommand.SetHeadlight(on), "set headlight")
    }

    /**
     * Select a pedals ride mode. Pass the family-specific integer
     * code (e.g. `0 = Soft`, `1 = Medium`, `2 = Hard` for most
     * families). The actual applied mode is confirmed by the next
     * telemetry frame, which updates [DashboardUiState.rideMode].
     */
    fun setPedalsMode(modeCode: Int) {
        dispatchSafely(WheelCommand.SetRideMode(modeCode), "set pedals mode")
    }

    /** Fire a single short beep on the wheel's speaker. */
    fun beep() {
        dispatchSafely(WheelCommand.Beep, "beep")
    }

    private fun dispatchSafely(command: WheelCommand, operation: String) {
        viewModelScope.launch {
            try {
                dispatch(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to $operation on $address", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Release the ref-counted handle. `resolvedConnection` is used
        // (rather than awaiting connectionAsync) because viewModelScope
        // is already cancelled at this point: awaiting a cancelled
        // Deferred would throw CancellationException and skip teardown.
        val conn = resolvedConnection
        if (conn == null) {
            viewModelScope.launch(NonCancellable + Dispatchers.IO) {
                try {
                    connectionAsync.await().close()
                } catch (_: Throwable) {
                    // Best-effort; connection may already be closed.
                }
            }
        } else {
            viewModelScope.launch(NonCancellable + Dispatchers.IO) {
                try {
                    conn.close()
                } catch (_: Throwable) {
                    // Best-effort; connection may already be closed.
                }
            }
        }
    }

    // ---- Helpers -------------------------------------------------------

    private fun combineDashboardFlows(conn: WheelConnection) = with(conn) {
        // Build the state out of the consolidated `telemetry`
        // StateFlow plus the discrete connection-level signals. The
        // per-field convenience flows (speedKmh, currentA, ...) on
        // [WheelConnection] are derived from the same `telemetry`
        // anyway, so we read it once and avoid the 5-arg combine
        // arity ceiling.
        kotlinx.coroutines.flow.combine(
            state,
            identity,
            telemetry,
            headlightOnFlow,
            kotlinx.coroutines.flow.combine(
                maxSpeedFlow,
                avgSpeedFlow,
                rideTimeFlow,
                settingsRepository.settings,
            ) { mx, av, rt, settings -> DashboardExtras(mx, av, rt, settings.useMetric, settings.keepScreenOnDashboard) },
        ) { connState, id, t, headlight, metrics ->
            DashboardUiState(
                connectionState = connState,
                identity = id,
                speedKmh = t.speedKmh?.let { kotlin.math.abs(it) },
                voltageV = t.voltageV,
                batteryPercent = t.batteryPercent,
                batteryVoltageV = t.batteryVoltageV,
                currentA = t.currentA,
                phaseCurrentA = t.phaseCurrentA,
                pwmPercent = t.pwmPercent,
                mosTemperatureC = t.mosTemperatureC,
                motorTemperatureC = t.motorTemperatureC,
                boardTemperatureC = t.boardTemperatureC,
                batteryTemperatureC = t.batteryTemperatureC,
                totalDistanceMetres = t.totalDistanceMetres,
                tripDistanceMetres = t.tripDistanceMetres,
                rideMode = t.rideMode,
                headlightOn = headlight,
                faults = t.faults ?: emptySet(),
                maxSpeedKmh = metrics.maxSpeedKmh,
                avgSpeedKmh = metrics.avgSpeedKmh,
                rideTimeSeconds = metrics.rideTimeSeconds,
                useMetric = metrics.useMetric,
                keepScreenOnDashboard = metrics.keepScreenOnDashboard,
            )
        }
    }

    private data class DashboardExtras(
        val maxSpeedKmh: Float?,
        val avgSpeedKmh: Float?,
        val rideTimeSeconds: Long,
        val useMetric: Boolean,
        val keepScreenOnDashboard: Boolean,
    )

    companion object {
        private const val TAG = "DashboardViewModel"

        const val ARG_ADDRESS: String = "address"
        const val ARG_FAMILY: String = "family"

        /** Maximum samples retained in the chart history buffer. */
        const val HISTORY_LIMIT: Int = 600

        /** Maximum entries retained in the events log. */
        const val ALERT_LOG_LIMIT: Int = 100

        /** How long the latest alert remains visible on the dashboard. */
        const val ALERT_TTL_MILLIS: Long = 6_000L

        /** Speed threshold above which the ride timer starts ticking, km/h. */
        const val RIDE_START_THRESHOLD_KMH: Float = 1f
    }
}
