/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.BatteryManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.rideflux.data.bridge.BridgeFrame
import com.rideflux.data.bridge.SignalLevel
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.settings.SettingsRepository
import com.rideflux.domain.wheel.WheelFamily
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.min

/** Phone bridge lifecycle, independent from the selected wheel's BLE lifecycle. */
enum class BridgeState { STOPPED, STANDBY, ATTACHING, RELAYING, DEGRADED }

private data class BridgeTarget(
    val mac: String,
    val family: WheelFamily?,
)

private data class WheelSnapshot(
    val connectionState: ConnectionState,
    val frame: BridgeFrame,
)

/**
 * Sticky foreground service that keeps the phone discoverable to the HUD.
 *
 * Advertising begins even without a wheel target. A target may then be attached,
 * replaced or cleared without rebuilding the GATT server. Wheel failures move the
 * service to [BridgeState.DEGRADED] and retry forever while 1 Hz standby frames keep
 * the phone-to-glasses link alive.
 */
@AndroidEntryPoint
class BridgeService : Service() {

    @Inject lateinit var wheelRepository: WheelRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val target = MutableStateFlow<BridgeTarget?>(null)
    @Volatile private var publisher: BridgePublisher? = null
    @Volatile private var openJob: Job? = null
    @Volatile private var publisherGeneration = 0L
    @Volatile private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _linkMode.value = GlassesLinkPreferences.read(this)
        scope.launch {
            settingsRepository.settings.collect {
                applyAdvertiseMode()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        when (intent?.action) {
            ACTION_SET_TARGET -> readTarget(intent)?.let { selected ->
                target.value = selected
                _activeMac.value = selected.mac
                setBridgeState(BridgeState.ATTACHING)
                Log.i(TAG, "wheel target set: ${selected.mac} family=${selected.family}")
            }
            ACTION_CLEAR_TARGET -> {
                target.value = null
                _activeMac.value = null
                // Do not publish STANDBY yet. flatMapLatest first runs
                // reconnectingWheelFrames' NonCancellable close; only then
                // does idleFrames() set STANDBY. ScannerRoute waits for that
                // state so it never scans while the wheel is still connected.
                Log.i(TAG, "wheel target clearing; waiting for GATT teardown")
            }
            ACTION_SET_LINK_MODE -> readLinkMode(intent)?.let(::switchPublisher)
            ACTION_START, null -> Unit
            else -> Log.w(TAG, "ignoring unknown action ${intent.action}")
        }

        ensureBridgeOpen()
        return START_STICKY
    }

    private fun readTarget(intent: Intent): BridgeTarget? {
        val mac = intent.getStringExtra(EXTRA_MAC)?.trim()?.uppercase()
        if (mac == null || !BluetoothAdapter.checkBluetoothAddress(mac)) {
            Log.w(TAG, "ignoring invalid or missing $EXTRA_MAC")
            return null
        }
        val family = intent.getStringExtra(EXTRA_FAMILY)
            ?.let { runCatching { WheelFamily.valueOf(it) }.getOrNull() }
        return BridgeTarget(mac, family)
    }

    private fun readLinkMode(intent: Intent): GlassesLinkMode? =
        intent.getStringExtra(EXTRA_LINK_MODE)
            ?.let { raw -> runCatching { GlassesLinkMode.valueOf(raw) }.getOrNull() }

    @Synchronized
    private fun switchPublisher(mode: GlassesLinkMode) {
        GlassesLinkPreferences.write(this, mode)
        if (_linkMode.value == mode && publisher != null) return
        _linkMode.value = mode
        publisherGeneration += 1L
        openJob?.cancel()
        openJob = null
        val previous = publisher
        publisher = null
        previous?.stop()
        _linkState.value = GlassesLinkState.STOPPED
        // Bluetooth resource teardown is asynchronous on both the Android
        // stack and Rokid's SDK. Give the old transport time to release its
        // advertiser/socket before opening the replacement.
        ensureBridgeOpen(
            initialDelayMillis = if (previous == null) 0L else PUBLISHER_SWITCH_SETTLE_MILLIS,
        )
        Log.i(TAG, "glasses link mode changed to $mode")
    }

    /** Opens the server with an infinite capped retry; callers never own this job. */
    @Synchronized
    private fun ensureBridgeOpen(initialDelayMillis: Long = 0L) {
        if (publisher != null || openJob?.isActive == true) return
        val generation = publisherGeneration
        openJob = scope.launch {
            if (initialDelayMillis > 0L) delay(initialDelayMillis)
            var attempt = 0L
            var candidate: BridgePublisher? = null
            try {
                while (
                    currentCoroutineContext().isActive &&
                    publisher == null &&
                    generation == publisherGeneration
                ) {
                    val openingMode = _linkMode.value
                    candidate = when (openingMode) {
                        GlassesLinkMode.ANDROID_BLE -> NativeBleBridgePublisher(
                            applicationContext,
                            ::setLinkState,
                        )
                        GlassesLinkMode.ROKID_CXR -> RokidCxrBridgePublisher(
                            applicationContext,
                            scope,
                            ::setLinkState,
                        )
                    }
                    if (candidate.open()) {
                        currentCoroutineContext().ensureActive()
                        if (generation != publisherGeneration) return@launch
                        publisher = candidate
                        candidate.attachSource(scope, frames())
                        candidate = null // Ownership transferred to publisher.
                        if (target.value == null) setBridgeState(BridgeState.STANDBY)
                        Log.i(TAG, "bridge publisher started: $openingMode")
                        return@launch
                    }
                    candidate.stop()
                    candidate = null
                    setBridgeState(BridgeState.DEGRADED)
                    val waitMs = reconnectBackoffMillis(attempt++)
                    Log.w(TAG, "bridge publisher open failed; retrying in ${waitMs}ms")
                    delay(waitMs)
                }
            } finally {
                // A mode switch can cancel this coroutine while candidate.open()
                // is in flight. Never leak that stale transport into the new mode.
                candidate?.stop()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun frames(): Flow<BridgeFrame> = target.flatMapLatest { selected ->
        if (selected == null) {
            setBridgeState(BridgeState.STANDBY)
            idleFrames()
        } else {
            reconnectingWheelFrames(selected)
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        Log.e(TAG, "bridge frame pipeline failed; falling back to standby", error)
        setBridgeState(BridgeState.DEGRADED)
        emitAll(idleFrames())
    }

    /** Never completes unless its owning coroutine is cancelled. */
    private fun reconnectingWheelFrames(selected: BridgeTarget): Flow<BridgeFrame> = flow {
        var attempt = 0L
        while (currentCoroutineContext().isActive) {
            setBridgeState(BridgeState.ATTACHING)
            var reachedReady = false
            var connection: WheelConnection? = null
            try {
                connection = wheelRepository.connect(selected.mac, selected.family)
                var reachedActiveState = false
                connection.snapshotFlow().collect { snapshot ->
                    when (snapshot.connectionState) {
                        ConnectionState.Ready -> {
                            reachedActiveState = true
                            reachedReady = true
                            setBridgeState(
                                if (snapshot.frame.ready) BridgeState.RELAYING
                                else BridgeState.DEGRADED,
                            )
                        }
                        ConnectionState.Connecting, is ConnectionState.Handshaking -> {
                            reachedActiveState = true
                            setBridgeState(BridgeState.ATTACHING)
                        }
                        ConnectionState.Disconnected -> setBridgeState(BridgeState.DEGRADED)
                        is ConnectionState.Failed -> setBridgeState(BridgeState.DEGRADED)
                    }

                    emit(snapshot.frame)
                    val terminal = snapshot.connectionState is ConnectionState.Failed ||
                        (snapshot.connectionState == ConnectionState.Disconnected && reachedActiveState)
                    if (terminal) throw WheelLinkEnded()
                }
                throw WheelLinkEnded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "wheel link ${selected.mac} ended: ${e.message}")
            } finally {
                withContext(NonCancellable) {
                    try { connection?.close() } catch (_: Throwable) { /* best-effort */ }
                }
            }

            setBridgeState(BridgeState.DEGRADED)
            val waitMs = reconnectBackoffMillis(if (reachedReady) 0L else attempt)
            attempt = if (reachedReady) 0L else attempt + 1L
            val deadline = SystemClock.elapsedRealtime() + waitMs
            do {
                emit(standbyFrame(phoneBatteryPercent = readPhoneBatteryPercent()))
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining > 0L) delay(min(IDLE_HEARTBEAT_MILLIS, remaining))
            } while (SystemClock.elapsedRealtime() < deadline)
        }
    }

    private fun WheelConnection.snapshotFlow(): Flow<WheelSnapshot> {
        var lastTelemetryTimestamp = Long.MIN_VALUE
        var lastTelemetryElapsed = 0L
        return combine(
            state,
            telemetry,
            phoneBatteryFlow(),
            flow {
                while (true) {
                    emit(Unit)
                    delay(STALE_TICK_MILLIS)
                }
            },
        ) { connState, telemetry, phoneBattery, _ ->
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            val haveFrame = telemetry.timestampMillis > 0L
            if (haveFrame && telemetry.timestampMillis != lastTelemetryTimestamp) {
                lastTelemetryTimestamp = telemetry.timestampMillis
                lastTelemetryElapsed = elapsed
            }
            val stale = connState == ConnectionState.Ready &&
                (!haveFrame || elapsed - lastTelemetryElapsed > STALE_THRESHOLD_MILLIS)
            WheelSnapshot(
                connectionState = connState,
                frame = BridgeFrame(
                    timestampMillis = if (haveFrame) telemetry.timestampMillis else now,
                    speedKmh = telemetry.speedKmh
                        ?.takeIf { it.isFinite() }
                        ?.let { kotlin.math.abs(it) },
                    vehicleBatteryPercent = telemetry.batteryPercent,
                    phoneBatteryPercent = phoneBattery,
                    voltageV = telemetry.voltageV,
                    tripDistanceMetres = telemetry.tripDistanceMetres,
                    tripDurationSeconds = null,
                    signal = when (connState) {
                        ConnectionState.Ready -> SignalLevel.GOOD
                        ConnectionState.Connecting, is ConnectionState.Handshaking -> SignalLevel.WEAK
                        ConnectionState.Disconnected, is ConnectionState.Failed -> SignalLevel.NONE
                    },
                    stale = stale,
                    ready = connState == ConnectionState.Ready && !stale,
                ),
            )
        }
    }

    private fun idleFrames(): Flow<BridgeFrame> = flow {
        while (true) {
            emit(standbyFrame(phoneBatteryPercent = readPhoneBatteryPercent()))
            delay(IDLE_HEARTBEAT_MILLIS)
        }
    }

    private fun phoneBatteryFlow(): Flow<Int?> = flow {
        while (true) {
            emit(readPhoneBatteryPercent())
            delay(PHONE_BATTERY_POLL_MILLIS)
        }
    }

    private fun readPhoneBatteryPercent(): Int? {
        val manager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return manager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
    }

    override fun onDestroy() {
        publisherGeneration += 1L
        openJob?.cancel()
        publisher?.stop()
        publisher = null
        openJob = null
        target.value = null
        _activeMac.value = null
        _state.value = BridgeState.STOPPED
        _linkState.value = GlassesLinkState.STOPPED
        foregroundStarted = false
        scope.cancel()
        Log.i(TAG, "bridge stopped")
        super.onDestroy()
    }

    private fun setBridgeState(value: BridgeState) {
        if (_state.value == value) return
        _state.value = value
        applyAdvertiseMode()
        val canPostNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (foregroundStarted && canPostNotification) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIF_ID, buildNotification(value))
        }
    }

    private fun applyAdvertiseMode() {
        val state = _state.value
        val lowLatency = state == BridgeState.RELAYING ||
            (settingsRepository.settings.value.bridgeStandbyAdvertiseLowLatency &&
                (state == BridgeState.STANDBY || state == BridgeState.DEGRADED))
        publisher?.setLowLatency(lowLatency)
    }

    private fun setLinkState(value: GlassesLinkState) {
        _linkState.value = value
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "RideFlux Bridge",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = buildNotification(_state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        foregroundStarted = true
    }

    private fun buildNotification(state: BridgeState): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val detail = when (state) {
            BridgeState.STOPPED, BridgeState.STANDBY -> "Waiting for a wheel; glasses can connect"
            BridgeState.ATTACHING -> "Connecting to the selected wheel"
            BridgeState.RELAYING -> "Relaying wheel telemetry to glasses"
            BridgeState.DEGRADED -> "Wheel unavailable; bridge remains discoverable"
        }
        return builder
            .setContentTitle("RideFlux → HUD bridge")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "BridgeService"
        private const val CHANNEL_ID = "rideflux_bridge"
        private const val NOTIF_ID = 7421

        const val EXTRA_MAC = "mac"
        const val EXTRA_FAMILY = "family"
        const val EXTRA_LINK_MODE = "link_mode"
        const val ACTION_START = "com.rideflux.app.bridge.START"
        const val ACTION_SET_TARGET = "com.rideflux.app.bridge.SET_TARGET"
        const val ACTION_CLEAR_TARGET = "com.rideflux.app.bridge.CLEAR_TARGET"
        const val ACTION_SET_LINK_MODE = "com.rideflux.app.bridge.SET_LINK_MODE"

        private const val STALE_THRESHOLD_MILLIS = 3_000L
        private const val STALE_TICK_MILLIS = 1_000L
        private const val IDLE_HEARTBEAT_MILLIS = 1_000L
        private const val PHONE_BATTERY_POLL_MILLIS = 15_000L
        private const val PUBLISHER_SWITCH_SETTLE_MILLIS = 1_000L

        private val _state = MutableStateFlow(BridgeState.STOPPED)
        val state: StateFlow<BridgeState> = _state.asStateFlow()

        private val _activeMac = MutableStateFlow<String?>(null)
        /** Wheel target currently assigned to the bridge, or null in standby/stopped. */
        val activeMac: StateFlow<String?> = _activeMac.asStateFlow()

        private val _linkMode = MutableStateFlow(GlassesLinkMode.ANDROID_BLE)
        val linkMode: StateFlow<GlassesLinkMode> = _linkMode.asStateFlow()

        private val _linkState = MutableStateFlow(GlassesLinkState.STOPPED)
        val linkState: StateFlow<GlassesLinkState> = _linkState.asStateFlow()

        fun startStandby(context: Context) {
            launch(context, Intent(context, BridgeService::class.java).apply { action = ACTION_START })
        }

        fun setTarget(context: Context, mac: String, family: WheelFamily?) {
            launch(
                context,
                Intent(context, BridgeService::class.java).apply {
                    action = ACTION_SET_TARGET
                    putExtra(EXTRA_MAC, mac)
                    family?.let { putExtra(EXTRA_FAMILY, it.name) }
                },
            )
        }

        /** Backward-compatible name used by existing dashboard callers. */
        fun start(context: Context, mac: String, family: WheelFamily?) =
            setTarget(context, mac, family)

        fun clearTarget(context: Context) {
            launch(
                context,
                Intent(context, BridgeService::class.java).apply { action = ACTION_CLEAR_TARGET },
            )
        }

        fun setLinkMode(context: Context, mode: GlassesLinkMode) {
            if (_state.value == BridgeState.STOPPED) {
                GlassesLinkPreferences.write(context, mode)
                _linkMode.value = mode
                return
            }
            launch(
                context,
                Intent(context, BridgeService::class.java).apply {
                    action = ACTION_SET_LINK_MODE
                    putExtra(EXTRA_LINK_MODE, mode.name)
                },
            )
        }

        /** The single full-stop path. Target clearing is deliberately separate. */
        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }

        private fun launch(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

internal fun standbyFrame(
    nowMillis: Long = System.currentTimeMillis(),
    phoneBatteryPercent: Int? = null,
): BridgeFrame = BridgeFrame(
    timestampMillis = nowMillis,
    speedKmh = null,
    vehicleBatteryPercent = null,
    phoneBatteryPercent = phoneBatteryPercent,
    voltageV = null,
    tripDistanceMetres = null,
    tripDurationSeconds = null,
    signal = SignalLevel.NONE,
    stale = true,
    ready = false,
)

internal fun reconnectBackoffMillis(attempt: Long): Long =
    (1_000L * (1L shl attempt.coerceIn(0L, 4L).toInt())).coerceAtMost(15_000L)

private class WheelLinkEnded : RuntimeException("wheel link ended")
