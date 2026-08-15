/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.alert.ThresholdStatusTracker
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.telemetry.RideMode
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.hud.source.BridgeTelemetrySource
import com.rideflux.hud.source.BridgePeerCandidate
import com.rideflux.hud.source.BridgePeerScanner
import com.rideflux.hud.source.DirectWheelTelemetrySource
import com.rideflux.hud.source.HudTelemetryFrame
import com.rideflux.hud.source.HudTelemetrySource
import com.rideflux.hud.storage.HudMacStore
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * UI projection consumed by [HudScreen]. Mirrors the essentials from
 * :app's `DashboardUiState` but stays intentionally narrow — the HUD
 * only renders speed, batteries, trip metrics and signal status.
 */
data class HudUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    // ---- Centre column ----
    val speedKmh: Float? = null,
    // ---- Right column (vehicle / EV) ----
    val vehicleBatteryPercent: Float? = null,
    val voltageV: Float? = null,
    val tripDistanceMetres: Int? = null,
    val tripDurationSeconds: Long? = null,
    val rideMode: RideMode? = null,
    // ---- Left column (devices / signal) ----
    /** Battery of the device running this APK (assumed to be the AR glasses). 0..100, or null. */
    val glassesBatteryPercent: Int? = null,
    /** Battery of a paired phone, when known. Null if no companion link is wired up. */
    val phoneBatteryPercent: Int? = null,
    val signalQuality: SignalQuality = SignalQuality.NONE,
    // ---- Status flags ----
    /** True when telemetry has not been refreshed within [HudViewModel.STALE_THRESHOLD_MILLIS]. */
    val isStale: Boolean = false,
    /** Three-state companion link; null when the HUD talks to a wheel directly. */
    val bridgeLinkState: BridgeLinkState? = null,
    /** `true` when no MAC was provided via intent extras. */
    val awaitingTarget: Boolean = false,
    val thresholdAlertActive: Boolean = false,
)

/** Coarse signal-quality buckets driven by [ConnectionState]. */
enum class SignalQuality { GOOD, WEAK, NONE }

/** Bridge mode distinguishes phone loss from a connected phone waiting for a wheel. */
enum class BridgeLinkState { NO_PHONE, PHONE_STANDBY, WHEEL_LIVE }

/**
 * Hilt-injected ViewModel for the standalone HUD APK.
 *
 * The activity places the target MAC into its intent extras under
 * [KEY_MAC]; Hilt's SavedStateHandle binding automatically exposes
 * those extras, so the ViewModel can read the address without a nav
 * graph.
 *
 * Connection lifecycle mirrors :app's DashboardViewModel: connect
 * lazily via a [Deferred], project every telemetry StateFlow onto a
 * single [uiState], close the connection exactly once on [onCleared].
 * When no MAC is provided, [uiState] parks on a non-connecting
 * sentinel so the UI can render instructions.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HudViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val wheelRepository: WheelRepository,
    private val macStore: HudMacStore,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sourceKind: String = resolveHudSourceKind(savedStateHandle.get(KEY_SOURCE))
    private val bridgeRequested: Boolean = sourceKind == SOURCE_BRIDGE

    /**
     * Resolved target MAC. Priority:
     *   1. `mac` intent extra (passed via SavedStateHandle) — wins
     *      and is persisted for next time.
     *   2. Last-used MAC from [HudMacStore].
     *   3. `null` — UI parks on the "awaiting target" message
     *      (unless the source is [SOURCE_BRIDGE], which doesn't
     *      need a wheel MAC at all).
     */
    private val targetAddress: String? = if (bridgeRequested) {
        null
    } else run {
        val macFromIntent = normaliseMac(savedStateHandle.get<String>(KEY_MAC))
        val familyFromIntent: WheelFamily? =
            savedStateHandle.get<String>(KEY_FAMILY)
                ?.let { runCatching { WheelFamily.valueOf(it) }.getOrNull() }
        // Ignore blank/whitespace MACs so a garbage extra cannot
        // override a good persisted value or produce invalid BLE calls.
        if (macFromIntent != null) {
            macStore.write(macFromIntent, familyFromIntent ?: macStore.readFamily())
            macFromIntent
        } else {
            normaliseMac(macStore.readMac())
        }
    }

    /** Family resolved from intent or store; only meaningful when [targetAddress] is non-null. */
    private val targetFamily: WheelFamily = macStore.readFamily()

    /**
     * Source kind ("bridge" — receive frames from a paired phone,
     * default — vs. "direct" — own the wheel GATT). Direct mode must
     * be explicitly selected with `--es source direct`; launcher
     * starts on RV101 therefore always finds the phone bridge.
     */
    /** True iff bridge mode is selected and therefore there is always a target. */
    private val isBridge: Boolean = sourceKind.equals(SOURCE_BRIDGE, ignoreCase = true)

    private val source: HudTelemetrySource? = when {
        isBridge -> BridgeTelemetrySource(appContext, macStore.readPairedPhoneMac())
        targetAddress != null -> DirectWheelTelemetrySource(
            wheelRepository = wheelRepository,
            mac = targetAddress,
            family = targetFamily,
        )
        else -> null
    }

    /**
     * The currently active source. Starts at [source] (resolved once
     * from intent extras / persisted state) and is replaced in place
     * when the rider pairs a phone, so the new allowlist MAC takes
     * effect immediately instead of after an activity restart.
     * `flatMapLatest` below cancels the previous source's frame
     * collection on every switch, which tears the old GATT down
     * through the same path as `onCleared`.
     */
    private val activeSource = MutableStateFlow(source)

    init {
        Log.i(TAG, "HUD source=$sourceKind target=${targetAddress ?: "none"}")
    }

    /** Monotonic time when the connection first reached Ready. */
    @Volatile private var sessionStartElapsed: Long? = null
    private var lastTelemetryTimestamp: Long = Long.MIN_VALUE
    private var lastTelemetryElapsed: Long = 0L
    private var lastThresholdTimestamp: Long = Long.MIN_VALUE
    private var thresholdActive: Boolean = false
    private val thresholdTracker = ThresholdStatusTracker()

    val settings: StateFlow<AppSettings> = settingsRepository.settings
    private val _pairingCandidates = MutableStateFlow<List<BridgePeerCandidate>>(emptyList())
    val pairingCandidates: StateFlow<List<BridgePeerCandidate>> = _pairingCandidates.asStateFlow()
    private var pairingJob: kotlinx.coroutines.Job? = null

    val uiState: StateFlow<HudUiState> =
        activeSource.flatMapLatest { active ->
            if (active == null) {
                flowOf(HudUiState(awaitingTarget = true))
            } else {
                combine(
                    active.frames().catch { error ->
                        emit(
                            HudTelemetryFrame(
                                state = ConnectionState.Failed(
                                    ConnectionState.Failed.Reason.INTERNAL,
                                    error.message,
                                ),
                                telemetry = WheelTelemetry.EMPTY,
                                signal = SignalQuality.NONE,
                                staleHint = true,
                                bridgeLinkState = if (isBridge) BridgeLinkState.NO_PHONE else null,
                            )
                        )
                    },
                    glassesBatteryFlow(),
                    tickerFlow(),
                ) { frame, glasses, _ ->
                    project(frame, glasses)
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
            initialValue = HudUiState(
                connectionState = if (isBridge) ConnectionState.Connecting
                else ConnectionState.Disconnected,
                bridgeLinkState = if (isBridge) BridgeLinkState.NO_PHONE else null,
                awaitingTarget = source == null,
            ),
        )

    private fun project(
        frame: HudTelemetryFrame,
        glassesBattery: Int?,
    ): HudUiState {
        val state = frame.state
        val telem = frame.telemetry

        // Capture the session-start once the link first goes Ready, and
        // reset it whenever the link drops so the trip clock reflects
        // actual connected riding time (not the disconnect window).
        if (state == ConnectionState.Ready && sessionStartElapsed == null) {
            sessionStartElapsed = SystemClock.elapsedRealtime()
        } else if (state != ConnectionState.Ready && sessionStartElapsed != null) {
            sessionStartElapsed = null
        }

        val tripDurationSec = sessionStartElapsed?.let {
            ((SystemClock.elapsedRealtime() - it) / 1_000L).coerceAtLeast(0L)
        }

        // Stale = either the source already flagged it (bridge mode
        // sees the unwrapped phone-side timestamp) or we have at
        // least one frame and it is older than the threshold. A Ready
        // link that has never delivered a frame is also stale (there
        // is nothing fresh to show yet).
        val now = SystemClock.elapsedRealtime()
        val haveFrame = telem.timestampMillis > 0L
        if (haveFrame && telem.timestampMillis != lastTelemetryTimestamp) {
            lastTelemetryTimestamp = telem.timestampMillis
            lastTelemetryElapsed = now
        }
        val isStale = frame.staleHint ||
            (state == ConnectionState.Ready && !haveFrame) ||
            (
                state == ConnectionState.Ready &&
                    haveFrame &&
                    (now - lastTelemetryElapsed) > STALE_THRESHOLD_MILLIS
            )
        if (telem.timestampMillis > 0L && telem.timestampMillis != lastThresholdTimestamp) {
            lastThresholdTimestamp = telem.timestampMillis
            thresholdActive = thresholdTracker.update(
                telem,
                settingsRepository.settings.value.alertThresholds,
            )
        }

        return HudUiState(
            connectionState = state,
            speedKmh = telem.speedKmh?.takeIf { it.isFinite() }?.let { kotlin.math.abs(it) },
            vehicleBatteryPercent = telem.batteryPercent,
            voltageV = telem.voltageV,
            tripDistanceMetres = telem.tripDistanceMetres,
            tripDurationSeconds = tripDurationSec,
            rideMode = telem.rideMode,
            glassesBatteryPercent = glassesBattery,
            phoneBatteryPercent = frame.phoneBatteryPercent,
            signalQuality = frame.signal,
            isStale = isStale,
            bridgeLinkState = frame.bridgeLinkState,
            awaitingTarget = false,
            thresholdAlertActive = thresholdActive,
        )
    }

    fun startPhonePairing() {
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            BridgePeerScanner(appContext).candidates()
                .catch { error -> Log.w(TAG, "Phone pairing scan failed", error) }
                .collect { _pairingCandidates.value = it }
        }
    }

    fun stopPhonePairing() {
        pairingJob?.cancel()
        pairingJob = null
        _pairingCandidates.value = emptyList()
    }

    fun pairPhone(address: String) {
        macStore.writePairedPhoneMac(address)
        viewModelScope.launch { settingsRepository.setHudPeerMac(address) }
        stopPhonePairing()
        if (isBridge) {
            // Rebuild the bridge source immediately so the freshly
            // stored phone MAC is honoured without an activity restart.
            // flatMapLatest cancels the old BridgeClient collection,
            // whose awaitClose releases the scan/GATT resources first.
            activeSource.value = BridgeTelemetrySource(appContext, macStore.readPairedPhoneMac())
        }
    }

    fun setSpeedLimit(value: Float) = viewModelScope.launch { settingsRepository.setSpeedLimitKmh(value) }
    fun setTemperatureLimit(value: Float) = viewModelScope.launch { settingsRepository.setTemperatureLimitC(value) }
    fun setLowBatteryLimit(value: Float) = viewModelScope.launch { settingsRepository.setLowBatteryPercent(value) }
    fun setPwmLimit(value: Float) = viewModelScope.launch { settingsRepository.setPwmAlertPercent(value) }

    /**
     * Polls the device's [BatteryManager] every 30 s. Cheap (one
     * IntProperty read), and sufficient for a glanceable HUD that
     * only buckets battery into ~10 % visual steps.
     */
    private fun glassesBatteryFlow(): Flow<Int?> = flow {
        val mgr = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        while (true) {
            val pct = mgr
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
            emit(pct)
            delay(30_000L)
        }
    }

    /** 1 Hz heartbeat used to refresh trip-duration and stale-data flags. */
    private fun tickerFlow(): Flow<Long> = flow {
        var i = 0L
        while (true) {
            emit(i++)
            delay(1_000L)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Direct mode: WheelRepository's ref-counting closes the
        // GATT when the last consumer of the connection goes away,
        // which happens automatically when `viewModelScope` is
        // cancelled and the source flow's collector finishes.
        // Bridge mode: BridgeClient's awaitClose handler tears down
        // the central GATT for us. No explicit close needed here.
    }

    companion object {
        private const val TAG = "HudViewModel"
        private fun normaliseMac(raw: String?): String? {
            val normalised = raw?.trim()?.uppercase(Locale.ROOT) ?: return null
            return normalised.takeIf(BluetoothAdapter::checkBluetoothAddress)
        }

        /**
         * SavedStateHandle / intent-extra key for the target BLE MAC.
         * Must match [HudActivity.EXTRA_MAC]; kept as a separate
         * constant so callers can depend on either without a cycle.
         */
        const val KEY_MAC: String = "mac"

        /**
         * Optional intent-extra key for the wheel family hint
         * (one of [WheelFamily] enum names). When absent, the
         * persisted family from [HudMacStore] is used; if that is
         * missing too, [HudMacStore.DEFAULT_FAMILY] applies.
         */
        const val KEY_FAMILY: String = "family"

        /**
         * Intent-extra key for the telemetry source kind.
         * Accepted values: [SOURCE_BRIDGE] (default) and
         * [SOURCE_DIRECT]. Bridge mode connects to the phone's BLE
         * advertising bridge instead of the wheel directly.
         */
        const val KEY_SOURCE: String = "source"

        /** Telemetry source: own the wheel GATT directly. */
        const val SOURCE_DIRECT: String = "direct"

        /** Telemetry source: receive frames from the phone's bridge. */
        const val SOURCE_BRIDGE: String = "bridge"

        /**
         * Telemetry older than this is treated as "stale" and a small
         * warning glyph appears on the HUD. 3 s comfortably exceeds
         * the worst-case frame interval of every supported family.
         */
        const val STALE_THRESHOLD_MILLIS: Long = 3_000L
    }
}

internal fun resolveHudSourceKind(explicit: String?): String =
    if (explicit?.trim().equals(HudViewModel.SOURCE_DIRECT, ignoreCase = true)) {
        HudViewModel.SOURCE_DIRECT
    } else {
        HudViewModel.SOURCE_BRIDGE
    }
