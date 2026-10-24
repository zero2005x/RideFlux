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
import com.rideflux.domain.command.supports
import com.rideflux.app.recording.RecordingService
import com.rideflux.domain.alert.ThresholdAlert
import com.rideflux.domain.alert.ThresholdMonitor
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.connection.WheelSessionSnapshot
import com.rideflux.domain.connection.ScooterHandshakeState
import com.rideflux.domain.connection.MiRegistrationState
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.garage.VehicleCalibration
import com.rideflux.domain.garage.VehicleProfile
import com.rideflux.domain.garage.VehicleProfileStore
import com.rideflux.domain.garage.updateIdentifiedIdentity
import com.rideflux.domain.garage.normalizeVehicleAddress
import com.rideflux.domain.telemetry.ScooterTelemetry
import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.repository.PlevConnectionHandle
import com.rideflux.domain.repository.PlevRepository
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.settings.SettingsRepository
import com.rideflux.domain.telemetry.RideMode
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.BegodeProfileStore
import com.rideflux.domain.wheel.BegodeProtocolProfile
import com.rideflux.domain.wheel.WheelBatteryPackStore
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentityEvidence
import com.rideflux.domain.wheel.WheelIdentity
import com.rideflux.domain.wheel.WheelSupport
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Why the last control command did not take effect.
 *
 * This is a UI-facing projection of [CommandOutcome]; `Success` is not a
 * failure and therefore has no entry. A [TRANSPORT_ERROR] means the bytes did
 * not reach the BLE transport, not that the wheel applied anything.
 */
enum class CommandFailureReason {
    UNSUPPORTED,
    INVALID_ARGUMENT,
    TRANSPORT_ERROR,
    NOT_CONNECTED,
}

data class CommandFailure(
    val operation: String,
    val reason: CommandFailureReason,
    val detail: String? = null,
)

/**
 * UI projection of a single wheel's live state.
 *
 * [capabilities] is the per-feature authorization matrix and [support] is the
 * evidence statement behind it. Both are `null` until identification. A
 * `Ready` connection with `support.isLimited == true` must render a
 * limited-support notice; `Ready` alone is not a full-support claim.
 *
 * [commandFailure] is cleared by the next successful dispatch. `headlightOn`
 * is only changed after a successful write (and rolled back on failure), so a
 * refused or lost command never leaves the switch looking applied.
 */
data class DashboardUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val identity: WheelIdentity? = null,
    val capabilities: WheelCapabilities? = null,
    val support: WheelSupport? = null,
    val vehicleProfile: VehicleProfile? = null,
    val calibration: VehicleCalibration = VehicleCalibration(),
    val commandFailure: CommandFailure? = null,
    val headlightPending: Boolean = false,
    val speedKmh: Float? = null,
    val pitchAngleDegrees: Float? = null,
    val rollAngleDegrees: Float? = null,
    val voltageV: Float? = null,
    val batteryPercent: Float? = null,
    val batteryVoltageV: Float? = null,
    val currentA: Float? = null,
    val phaseCurrentA: Float? = null,
    val pwmPercent: Float? = null,
    val mosTemperatureC: Float? = null,
    val imuTemperatureC: Float? = null,
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
    val deviceCategory: PlevCategory = PlevCategory.WHEEL,
    val handshakeState: ScooterHandshakeState? = null,
    val handshakeRemainingSeconds: Int? = null,
    val isLocked: Boolean? = null,
    val lockControlsSupported: Boolean = false,
    val deviceModel: String? = null,
    val miRegistrationState: MiRegistrationState = MiRegistrationState.NOT_REQUIRED,
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

/** Pure PLEV projection, shared by the ViewModel and JVM tests. */
fun scooterDashboardState(
    scooter: ScooterConnection,
    connectionState: ConnectionState,
    telemetry: ScooterTelemetry?,
    handshake: ScooterHandshakeState,
    remaining: Int?,
    useMetric: Boolean = true,
    keepScreenOnDashboard: Boolean = true,
    miRegistration: MiRegistrationState = MiRegistrationState.NOT_REQUIRED,
): DashboardUiState = DashboardUiState(
    connectionState = connectionState,
    deviceCategory = PlevCategory.SCOOTER,
    deviceModel = scooter.device.model,
    speedKmh = telemetry?.speedKmh,
    batteryPercent = telemetry?.batteryPercent,
    totalDistanceMetres = telemetry?.totalDistanceMetres,
    tripDistanceMetres = telemetry?.tripDistanceMetres?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
    handshakeState = handshake,
    lockControlsSupported = scooter.lockSupported,
    handshakeRemainingSeconds = if (handshake == ScooterHandshakeState.WAITING_FOR_USER_CONFIRMATION ||
        miRegistration == MiRegistrationState.WAITING_FOR_POWER_BUTTON) remaining else null,
    useMetric = useMetric,
    keepScreenOnDashboard = keepScreenOnDashboard,
    miRegistrationState = miRegistration,
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
    private val settingsRepository: SettingsRepository,
    private val batteryPacks: WheelBatteryPackStore,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
    private val plevRepository: PlevRepository = com.rideflux.protocol.repository.PlevRepositoryImpl(wheelRepository),
    private val begodeProfiles: BegodeProfileStore = BegodeProfileStore.empty(),
    private val vehicleProfiles: VehicleProfileStore = VehicleProfileStore.empty(),
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

    /** Explicit navigation category, when the scanner knew it. */
    private val explicitCategory: PlevCategory? =
        savedStateHandle.get<String>(ARG_CATEGORY)?.let {
            runCatching { PlevCategory.valueOf(it) }.getOrNull()
        }

    /**
     * Category used to route the connect. Restored from the saved vehicle
     * profile when navigation did not carry one, so a scooter in a fresh
     * process does not fall through to the wheel repository merely because
     * no scan has run yet.
     */
    @Volatile
    var deviceCategory: PlevCategory = explicitCategory ?: PlevCategory.WHEEL
        private set

    private val profileAddress: String = normalizeVehicleAddress(address)

    /** Saved profile for this address, or `null` when this is an ad-hoc connect. */
    private val _vehicleProfile = MutableStateFlow<VehicleProfile?>(null)
    val vehicleProfile: StateFlow<VehicleProfile?> = _vehicleProfile.asStateFlow()

    private val handshakeCountdown = MutableStateFlow<Int?>(null)

    /**
     * Cells in series of this wheel's battery as stated by the rider, or `null`
     * until they have said. Families whose frame carries only a voltage (Begode)
     * cannot report a battery percentage without it.
     */
    val batteryPackCells: StateFlow<Int?> = batteryPacks.seriesCells
        .map { it[WheelBatteryPackStore.key(address)] }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            batteryPacks.seriesCells.value[WheelBatteryPackStore.key(address)],
        )

    /** Records the rider's answer for this wheel; the codec picks it up on the next frame. */
    fun setBatteryPackCells(cells: Int) {
        viewModelScope.launch { batteryPacks.setSeriesCells(address, cells) }
    }

    val begodeProfile: StateFlow<BegodeProtocolProfile> = begodeProfiles.profiles
        .map { it[BegodeProfileStore.key(address)] ?: BegodeProtocolProfile.UNKNOWN }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            begodeProfiles.profileFor(address),
        )

    /** Records the rider's explicit Begode layout. The codec reads it on the next frame. */
    fun setBegodeProfile(profile: BegodeProtocolProfile) {
        viewModelScope.launch { begodeProfiles.setProfile(address, profile) }
    }

    /**
     * Connect exactly once. Subsequent property reads await the same
     * [Deferred] without racing another [WheelRepository.connect]
     * call. Runs under [NonCancellable] so the connection is not lost
     * if the ViewModel is cleared while the connect is in flight —
     * [onCleared] can still await the result and close the handle.
     */
    private val connectionAsync: Deferred<PlevConnectionHandle> =
        viewModelScope.async(NonCancellable + Dispatchers.IO) {
            val profile = vehicleProfiles.profile(profileAddress)
            _vehicleProfile.value = profile
            val category = explicitCategory ?: profile?.category ?: PlevCategory.WHEEL
            deviceCategory = category
            val family = expectedFamily ?: profile?.family
            // Pass the persisted/explicit category into the router. The
            // address-only PlevRepository API depends on this process's scan
            // cache; a fresh process with a saved scooter would otherwise
            // fall through to wheels.connect.
            plevRepository.connect(
                address = address,
                category = category,
                expectedFamily = family,
                modelHint = profile?.transportHint,
            ).also { resolvedConnection = it }
        }

    /**
     * Resolved connection, captured as soon as [connectionAsync]
     * completes. Kept separately so [onCleared] can always release
     * the ref-counted handle even when `viewModelScope` (and the
     * deferred with it) has already been cancelled — awaiting a
     * cancelled deferred would throw and skip the teardown.
     */
    @Volatile
    private var resolvedConnection: PlevConnectionHandle? = null

    /** The wheel session currently allowed to write identity into the garage. */
    @Volatile
    private var activeWheel: com.rideflux.domain.connection.WheelConnection? = null

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
    private val headlightPendingFlow = MutableStateFlow(false)

    /** Last rejected/failed command; cleared by the next successful dispatch. */
    private val commandFailureFlow = MutableStateFlow<CommandFailure?>(null)

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
        vehicleProfiles.profiles
            .onEach { saved ->
                _vehicleProfile.value = saved.firstOrNull { it.address == profileAddress }
            }
            .launchIn(viewModelScope)
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
            if (conn is PlevConnectionHandle.Scooter) {
                var countdownJob: Job? = null
                conn.connection.handshakeState.onEach { state ->
                    if (state == ScooterHandshakeState.WAITING_FOR_USER_CONFIRMATION) {
                        countdownJob?.cancel()
                        handshakeCountdown.value = 20
                        countdownJob = viewModelScope.launch {
                            repeat(20) {
                                delay(1_000L)
                                handshakeCountdown.value = 19 - it
                            }
                        }
                    } else if (conn.connection.registrationState.value != MiRegistrationState.WAITING_FOR_POWER_BUTTON) {
                        countdownJob?.cancel()
                        handshakeCountdown.value = null
                    }
                }.launchIn(viewModelScope)

                conn.connection.registrationState.onEach { regState ->
                    if (regState == MiRegistrationState.WAITING_FOR_POWER_BUTTON) {
                        countdownJob?.cancel()
                        handshakeCountdown.value = 30
                        countdownJob = viewModelScope.launch {
                            repeat(30) {
                                delay(1_000L)
                                handshakeCountdown.value = 29 - it
                            }
                        }
                    } else if (conn.connection.handshakeState.value != ScooterHandshakeState.WAITING_FOR_USER_CONFIRMATION) {
                        countdownJob?.cancel()
                        handshakeCountdown.value = null
                    }
                }.launchIn(viewModelScope)
                return@launch
            }
            val wheel = (conn as PlevConnectionHandle.Wheel).connection
            activeWheel = wheel
            // Persist only evidence-backed session identity. The alias,
            // per-vehicle thresholds, calibration and notes are preserved by
            // the store; a retired session must not update a newer profile.
            observeIdentityEvidence(wheel, profileAddress)
            wheel.alerts.onEach { alert ->
                publishAlert(DashboardAlert.Wheel(alert))
            }.launchIn(viewModelScope)

            // Per-vehicle thresholds override the global defaults. The
            // vehicle-scoped value is read from the same persisted profile
            // store that routed the connection, so two wheels in the garage
            // cannot share one alert limit by accident.
            val thresholdFlow = combine(
                settingsRepository.settings,
                vehicleProfiles.profiles,
            ) { settings, saved ->
                saved.firstOrNull { it.address == profileAddress }?.alertThresholds
                    ?: settings.alertThresholds
            }.stateIn(viewModelScope, SharingStarted.Eagerly, AlertThresholds())
            val thresholdMonitor = ThresholdMonitor(
                telemetry = wheel.telemetry,
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
            wheel.telemetry.onEach { t ->
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
        return if (conn is PlevConnectionHandle.Wheel) conn.connection.dispatch(command)
        else CommandOutcome.Unsupported(command)
    }

    fun onLockClicked() {
        viewModelScope.launch {
            val scooter = connectionAsync.await() as? PlevConnectionHandle.Scooter ?: return@launch
            scooter.connection.lock()
        }
    }

    fun onUnlockClicked() {
        viewModelScope.launch {
            val scooter = connectionAsync.await() as? PlevConnectionHandle.Scooter ?: return@launch
            scooter.connection.unlock()
        }
    }

    fun onMiRegistrationConsent(confirmed: Boolean) {
        viewModelScope.launch {
            val scooter = connectionAsync.await() as? PlevConnectionHandle.Scooter ?: return@launch
            if (confirmed) {
                scooter.connection.registerAfterUserConfirmation(true)
            } else {
                scooter.connection.registerAfterUserConfirmation(false)
                scooter.connection.close()
            }
        }
    }

    // ---- High-level command helpers -----------------------------------

    /**
     * Request the wheel's primary headlight.
     *
     * The switch does not move until the write succeeded, and moves back to
     * the previous value when it did not. A transport-level `Success` still
     * means only "the bytes were handed to the BLE stack": the I2 protocol
     * has no headlight readback, so the UI must not present this as a
     * device-confirmed state.
     */
    fun setHeadlight(on: Boolean) {
        val previous = headlightOnFlow.value
        headlightPendingFlow.value = true
        launchCommand(WheelCommand.SetHeadlight(on), "set headlight") { outcome ->
            headlightPendingFlow.value = false
            headlightOnFlow.value = if (outcome is CommandOutcome.Success) on else previous
        }
    }

    /**
     * Select a pedals ride mode. The actual applied mode is confirmed by the
     * next telemetry frame, which updates [DashboardUiState.rideMode].
     */
    fun setPedalsMode(modeCode: Int) {
        launchCommand(WheelCommand.SetRideMode(modeCode), "set pedals mode")
    }

    /** Fire a single short beep on the wheel's speaker. */
    fun beep() {
        launchCommand(WheelCommand.Beep, "beep")
    }

    /**
     * Power off the wheel remotely.
     * Guarded by vehicle speed: will not execute if speed > 0 km/h.
     */
    fun powerOff() {
        if (rejectIfMoving("power off")) return
        launchCommand(WheelCommand.PowerOff, "power off")
    }

    /**
     * Calibrate the wheel's gyroscope / horizontal zero.
     * Guarded by vehicle speed: will not execute if speed > 0 km/h.
     */
    fun calibrate() {
        if (rejectIfMoving("calibrate")) return
        launchCommand(WheelCommand.Calibrate, "calibrate")
    }

    /**
     * Set the maximum speed limit in km/h.
     * Guarded by vehicle speed: will not execute if speed > 0 km/h.
     */
    fun setMaxSpeedKmh(kmh: Float) {
        if (rejectIfMoving("set max speed")) return
        launchCommand(WheelCommand.SetMaxSpeedKmh(kmh), "set max speed")
    }

    private fun rejectIfMoving(operation: String): Boolean {
        val speed = uiState.value.speedKmh ?: 0f
        if (speed > 0f) {
            val failure = CommandFailure(
                operation = operation,
                reason = CommandFailureReason.INVALID_ARGUMENT,
                detail = "vehicle is moving (${speed} km/h)",
            )
            commandFailureFlow.value = failure
            Log.w(TAG, "$operation blocked: vehicle is in motion (speed=$speed km/h)")
            return true
        }
        return false
    }

    /**
     * Run one user-visible command, gate it on the published capability
     * matrix, and project its outcome into [DashboardUiState.commandFailure].
     *
     * The gate here is a UX guard; the connection and codec repeat it. In
     * particular a `null` matrix (before identification) is left to the codec,
     * because some families accept a command during their bootstrap.
     */
    private fun launchCommand(
        command: WheelCommand,
        operation: String,
        onOutcome: ((CommandOutcome) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val outcome = dispatchWithOutcome(command, operation)
            onOutcome?.invoke(outcome)
        }
    }

    private suspend fun dispatchWithOutcome(
        command: WheelCommand,
        operation: String,
    ): CommandOutcome {
        val capabilities = uiState.value.capabilities
        if (capabilities != null && !capabilities.supports(command)) {
            val outcome = CommandOutcome.Unsupported(command)
            commandFailureFlow.value = CommandFailure(operation, CommandFailureReason.UNSUPPORTED)
            return outcome
        }
        val outcome = try {
            dispatch(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to $operation on $address", e)
            CommandOutcome.TransportError(command, e)
        }
        when (outcome) {
            is CommandOutcome.Success -> commandFailureFlow.value = null
            is CommandOutcome.Unsupported -> {
                commandFailureFlow.value = CommandFailure(operation, CommandFailureReason.UNSUPPORTED)
            }
            is CommandOutcome.InvalidArgument -> {
                commandFailureFlow.value = CommandFailure(
                    operation,
                    CommandFailureReason.INVALID_ARGUMENT,
                    outcome.message,
                )
            }
            is CommandOutcome.TransportError -> {
                commandFailureFlow.value = CommandFailure(
                    operation,
                    CommandFailureReason.TRANSPORT_ERROR,
                    outcome.cause?.message,
                )
            }
        }
        return outcome
    }

    /**
     * Prefer the connection's immutable [WheelSessionSnapshot]. A legacy
     * connection that predates it is handled with publication-version pairing:
     * a pair is eligible only when both flows have the same emission version,
     * so a new identity cannot borrow the previous support (or vice versa).
     */
    private fun observeIdentityEvidence(wheel: WheelConnection, profileAddress: String) {
        val snapshots = wheel.sessionSnapshot
        if (snapshots != null) {
            var lastSequence = Long.MIN_VALUE
            snapshots.onEach { snapshot ->
                if (activeWheel !== wheel || snapshot == null) return@onEach
                if (snapshot.sequence <= lastSequence) return@onEach
                lastSequence = snapshot.sequence
                persistIdentitySnapshot(
                    profileAddress,
                    snapshot.identity,
                    snapshot.support?.identityEvidence,
                )
            }.launchIn(viewModelScope)
            return
        }

        val identityVersion = AtomicLong(0)
        val supportVersion = AtomicLong(0)
        wheel.identity.onEach { identity ->
            val version = identityVersion.incrementAndGet()
            if (supportVersion.get() == version) {
                persistIdentitySnapshot(profileAddress, identity, wheel.support.value?.identityEvidence)
            }
        }.launchIn(viewModelScope)
        wheel.support.onEach { support ->
            val version = supportVersion.incrementAndGet()
            if (identityVersion.get() == version) {
                persistIdentitySnapshot(profileAddress, wheel.identity.value, support?.identityEvidence)
            }
        }.launchIn(viewModelScope)
    }

    private suspend fun persistIdentitySnapshot(
        address: String,
        identity: WheelIdentity?,
        evidence: WheelIdentityEvidence?,
    ) {
        if (evidence != WheelIdentityEvidence.MODEL_AND_FIRMWARE) return
        val model = identity?.modelName?.takeIf { it.isNotBlank() } ?: return
        val firmware = identity.firmwareVersion?.takeIf { it.isNotBlank() } ?: return
        vehicleProfiles.updateIdentifiedIdentity(
            address = address,
            modelName = model,
            firmwareVersion = firmware,
            family = identity.family,
            evidence = evidence,
        )
    }

    override fun onCleared() {
        activeWheel = null
        val conn = resolvedConnection
        val deferred = connectionAsync
        super.onCleared()
        // viewModelScope is cancelled by super.onCleared(), so launching
        // teardown there would be a no-op and the GATT link would leak.
        // Use a detached scope that outlives the ViewModel.
        val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        if (conn == null) {
            teardownScope.launch {
                try {
                    deferred.await().close()
                } catch (_: Throwable) {
                } finally {
                    teardownScope.cancel()
                }
            }
        } else {
            teardownScope.launch {
                try {
                    conn.close()
                } catch (_: Throwable) {
                } finally {
                    teardownScope.cancel()
                }
            }
        }
    }

    // ---- Helpers -------------------------------------------------------

    /**
     * The one publication of identity, capabilities and support for [conn],
     * or `null` for a connection that predates [WheelConnection.sessionSnapshot].
     *
     * Sequence rules are scoped to one connection session: a stale or repeated
     * snapshot is ignored, a cleared snapshot empties the facts for good (a new
     * session arrives as a new connection, so it starts from its own sequence),
     * and the fold restarts on every collection rather than living in a field.
     */
    private fun atomicFacts(conn: WheelConnection): kotlinx.coroutines.flow.Flow<IdentityFacts>? {
        val snapshots = conn.sessionSnapshot ?: return null
        return snapshots
            .runningFold(AcceptedFacts()) { accepted, next -> acceptSnapshot(accepted, next) }
            .map { it.facts }
            .distinctUntilChanged()
    }

    private fun acceptSnapshot(accepted: AcceptedFacts, next: WheelSessionSnapshot?): AcceptedFacts = when {
        accepted.closed -> accepted
        // The StateFlow starts null; that is "not published yet", not a teardown.
        next == null -> if (!accepted.seen) accepted
            else accepted.copy(facts = IdentityFacts(), closed = true)
        next.sequence <= accepted.sequence -> accepted
        else -> AcceptedFacts(
            sequence = next.sequence,
            facts = IdentityFacts(next.identity, next.capabilities, next.support),
            seen = true,
        )
    }

    private fun combineDashboardFlows(handle: PlevConnectionHandle): kotlinx.coroutines.flow.Flow<DashboardUiState> {
        if (handle is PlevConnectionHandle.Scooter) {
            val scooter = handle.connection
            val pairingFlow = kotlinx.coroutines.flow.combine(
                scooter.handshakeState,
                scooter.registrationState,
                handshakeCountdown,
            ) { handshake, registration, countdown ->
                Triple(handshake, registration, countdown)
            }
            return kotlinx.coroutines.flow.combine(
                scooter.state,
                scooter.telemetry,
                pairingFlow,
                settingsRepository.settings,
            ) { state, telemetry, (handshake, registration, countdown), settings ->
                scooterDashboardState(
                    scooter = scooter,
                    connectionState = state,
                    telemetry = telemetry,
                    handshake = handshake,
                    remaining = countdown,
                    useMetric = settings.useMetric,
                    keepScreenOnDashboard = settings.keepScreenOnDashboard,
                    miRegistration = registration,
                )
            }
        }
        val conn = (handle as PlevConnectionHandle.Wheel).connection
        return with(conn) {
            // Keep each combine at or below the five-flow arity limit while
            // still reading the consolidated telemetry snapshot once.
            // Identity, capabilities and support are one publication. A
            // connection that predates sessionSnapshot keeps the legacy
            // three-flow view; otherwise the snapshot is the only source, so
            // the screen can never combine facts from two Identified events.
            val facts = atomicFacts(conn)
                ?: kotlinx.coroutines.flow.combine(identity, capabilities, support) { id, caps, sup ->
                    IdentityFacts(id, caps, sup)
                }
            val live = kotlinx.coroutines.flow.combine(state, facts, telemetry) { s, f, t ->
                LiveState(s, f, t)
            }
            val controls = kotlinx.coroutines.flow.combine(
                headlightOnFlow,
                headlightPendingFlow,
                commandFailureFlow,
            ) { headlight, pending, failure ->
                ControlExtras(headlight, pending, failure)
            }
            val metrics = kotlinx.coroutines.flow.combine(
                maxSpeedFlow,
                avgSpeedFlow,
                rideTimeFlow,
                settingsRepository.settings,
            ) { mx, av, rt, settings ->
                DashboardExtras(mx, av, rt, settings.useMetric, settings.keepScreenOnDashboard)
            }
            kotlinx.coroutines.flow.combine(live, controls, metrics, _vehicleProfile) {
                    current, control, metric, profile ->
                val t = current.telemetry
                val calibration = profile?.calibration ?: VehicleCalibration()
                DashboardUiState(
                    connectionState = current.state,
                    identity = current.facts.identity,
                    capabilities = current.facts.capabilities,
                    support = current.facts.support,
                    vehicleProfile = profile,
                    calibration = calibration,
                    commandFailure = control.commandFailure,
                    headlightPending = control.headlightPending,
                    speedKmh = t.speedKmh?.let { kotlin.math.abs(it) },
                    pitchAngleDegrees = t.pitchAngleDegrees?.plus(calibration.pitchOffsetDegrees),
                    rollAngleDegrees = t.rollAngleDegrees?.plus(calibration.rollOffsetDegrees),
                    voltageV = t.voltageV,
                    batteryPercent = t.batteryPercent,
                    batteryVoltageV = t.batteryVoltageV,
                    currentA = t.currentA,
                    phaseCurrentA = t.phaseCurrentA,
                    pwmPercent = t.pwmPercent,
                    mosTemperatureC = t.mosTemperatureC,
                    imuTemperatureC = t.imuTemperatureC,
                    motorTemperatureC = t.motorTemperatureC,
                    boardTemperatureC = t.boardTemperatureC,
                    batteryTemperatureC = t.batteryTemperatureC,
                    totalDistanceMetres = t.totalDistanceMetres,
                    tripDistanceMetres = t.tripDistanceMetres,
                    rideMode = t.rideMode,
                    headlightOn = control.headlightOn,
                    faults = t.faults ?: emptySet(),
                    maxSpeedKmh = metric.maxSpeedKmh,
                    avgSpeedKmh = metric.avgSpeedKmh,
                    rideTimeSeconds = metric.rideTimeSeconds,
                    useMetric = metric.useMetric,
                    keepScreenOnDashboard = metric.keepScreenOnDashboard,
                )
            }
        }
    }

    private data class ControlExtras(
        val headlightOn: Boolean,
        val headlightPending: Boolean,
        val commandFailure: CommandFailure?,
    )

    /** The three identity facts that must always come from one publication. */
    private data class IdentityFacts(
        val identity: WheelIdentity? = null,
        val capabilities: WheelCapabilities? = null,
        val support: WheelSupport? = null,
    )

    /** Connection state plus one identity publication and its telemetry. */
    private data class LiveState(
        val state: ConnectionState,
        val facts: IdentityFacts,
        val telemetry: WheelTelemetry,
    )

    /** Running acceptance of one connection session's snapshots. */
    private data class AcceptedFacts(
        val sequence: Long = Long.MIN_VALUE,
        val facts: IdentityFacts = IdentityFacts(),
        val seen: Boolean = false,
        val closed: Boolean = false,
    )

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
        const val ARG_CATEGORY: String = "category"

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

/**
 * Pure policy predicate: determines if hazardous vehicle controls
 * (power off, calibration, speed limit change) are permitted.
 *
 * Strictly disallowed if the connection is not Ready, or if the vehicle
 * reports non-zero speed (moving).
 */
fun isVehicleActionPermitted(connectionState: ConnectionState, speedKmh: Float?): Boolean =
    connectionState == ConnectionState.Ready && speedKmh != null && speedKmh <= 0f

