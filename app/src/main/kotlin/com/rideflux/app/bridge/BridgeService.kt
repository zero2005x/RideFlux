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
import android.app.PendingIntent
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
import com.rideflux.app.MainActivity
import com.rideflux.data.bridge.BridgeFrame
import com.rideflux.data.bridge.BridgePairingToken
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
import java.util.concurrent.atomic.AtomicBoolean
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
            ACTION_APPROVE_PEER -> {
                val address = intent.getStringExtra(EXTRA_AUTH_ADDRESS)
                val tokenHex = intent.getStringExtra(EXTRA_AUTH_TOKEN_HEX)
                val shortCode = intent.getStringExtra(EXTRA_AUTH_SHORT_CODE) ?: "????"
                if (address != null) {
                    publisher?.approvePeer(address)
                    ApprovedGlassesStore.add(
                        applicationContext,
                        ApprovedGlasses(tokenHex = tokenHex, mac = address, shortCode = shortCode),
                    )
                    if (_pendingAuthorization.value?.deviceAddress == address) {
                        _pendingAuthorization.value = null
                    }
                    cancelAuthorizationNotification()
                    Log.i(TAG, "peer approved: $address (code=$shortCode)")
                }
            }
            ACTION_REJECT_PEER -> {
                val address = intent.getStringExtra(EXTRA_AUTH_ADDRESS)
                if (address != null) {
                    publisher?.rejectPeer(address)
                    if (_pendingAuthorization.value?.deviceAddress == address) {
                        _pendingAuthorization.value = null
                    }
                    cancelAuthorizationNotification()
                    Log.i(TAG, "peer rejected: $address")
                }
            }
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
            var consecutiveCxrFailures = 0
            var candidate: BridgePublisher? = null
            try {
                while (
                    currentCoroutineContext().isActive &&
                    publisher == null &&
                    generation == publisherGeneration
                ) {
                    val openingMode = _linkMode.value
                    val opening: BridgePublisher = when (openingMode) {
                        GlassesLinkMode.ANDROID_BLE -> NativeBleBridgePublisher(
                            context = applicationContext,
                            onState = ::setLinkState,
                            onAuthorizationRequested = ::handleAuthorizationRequested,
                            onAuthorizationDismissed = ::handleAuthorizationDismissed,
                        )
                        GlassesLinkMode.ROKID_CXR -> RokidCxrBridgePublisher(
                            applicationContext,
                            scope,
                            ::setLinkState,
                            preferredGlassesMacProvider = { settingsRepository.settings.value.preferredGlassesMac },
                        )
                    }
                    candidate = opening
                    // BridgeServer.open() blocks until the GATT service
                    // registration is confirmed, so keep it off the
                    // Default dispatcher's small, CPU-sized pool.
                    if (withContext(Dispatchers.IO) { opening.open() }) {
                        currentCoroutineContext().ensureActive()
                        if (generation != publisherGeneration) return@launch
                        publisher = opening
                        opening.attachSource(scope, frames())
                        candidate = null // Ownership transferred to publisher.
                        consecutiveCxrFailures = 0
                        if (target.value == null) setBridgeState(BridgeState.STANDBY)
                        Log.i(TAG, "bridge publisher started: $openingMode")
                        return@launch
                    }
                    opening.stop()
                    candidate = null
                    setBridgeState(BridgeState.DEGRADED)
                    if (openingMode == GlassesLinkMode.ROKID_CXR) {
                        consecutiveCxrFailures += 1
                        if (shouldDegradeFromCxr(consecutiveCxrFailures)) {
                            // In CXR mode the phone performs no BLE
                            // advertising at all, so a persistently
                            // failing CXR publisher (no bonded glasses,
                            // missing provisioned-device credentials)
                            // leaves the HUD with nothing to find.
                            // Degrade back to the BLE bridge and make the
                            // mode switch visible in the UI rather than
                            // staying silently unreachable.
                            Log.e(
                                TAG,
                                "CXR publisher failed $consecutiveCxrFailures times; " +
                                    "degrading to ANDROID_BLE",
                            )
                            GlassesLinkPreferences.write(applicationContext, GlassesLinkMode.ANDROID_BLE)
                            _linkMode.value = GlassesLinkMode.ANDROID_BLE
                            consecutiveCxrFailures = 0
                            attempt = 0L
                            continue
                        }
                    } else {
                        consecutiveCxrFailures = 0
                    }
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
    private fun frames(): Flow<BridgeFrame> = combine(
        target.flatMapLatest { selected ->
            if (selected == null) {
                setBridgeState(BridgeState.STANDBY)
                idleFrames()
            } else {
                reconnectingWheelFrames(selected)
            }
        },
        _hudVisible,
    ) { frame, visible ->
        // Stamping visibility here rather than pushing a separate
        // command means a toggle emits a frame immediately, so the HUD
        // reacts at once instead of at the next telemetry tick — which
        // in standby would be up to a second away.
        frame.copy(hudHidden = !visible)
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
        cancelAuthorizationNotification()
        _pendingAuthorization.value = null
        scope.cancel()
        Log.i(TAG, "bridge stopped")
        super.onDestroy()
    }

    private fun handleAuthorizationRequested(request: GlassesAuthorizationRequest) {
        Log.i(TAG, "incoming glasses authorization request from ${request.deviceAddress} (code=${request.shortCode})")
        _pendingAuthorization.value = request
        postAuthorizationNotification(request)
    }

    private fun handleAuthorizationDismissed(deviceAddress: String) {
        if (_pendingAuthorization.value?.deviceAddress == deviceAddress) {
            Log.i(TAG, "glasses authorization request for $deviceAddress dismissed")
            _pendingAuthorization.value = null
            cancelAuthorizationNotification()
        }
    }

    private fun ensureAuthNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                AUTH_CHANNEL_ID,
                getString(com.rideflux.app.R.string.glasses_auth_notification_title),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(com.rideflux.app.R.string.glasses_auth_notification_title)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun postAuthorizationNotification(request: GlassesAuthorizationRequest) {
        val canPostNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!canPostNotification) {
            Log.w(TAG, "cannot post authorization notification: POST_NOTIFICATIONS not granted")
            return
        }

        ensureAuthNotificationChannel()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val approveIntent = Intent(this, BridgeService::class.java).apply {
            action = ACTION_APPROVE_PEER
            putExtra(EXTRA_AUTH_ADDRESS, request.deviceAddress)
            putExtra(EXTRA_AUTH_TOKEN_HEX, request.token?.let(BridgePairingToken::toHex))
            putExtra(EXTRA_AUTH_SHORT_CODE, request.shortCode)
            putExtra(EXTRA_AUTH_IS_LEGACY, request.isLegacy)
        }
        val approvePendingIntent = PendingIntent.getService(
            this,
            1,
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val rejectIntent = Intent(this, BridgeService::class.java).apply {
            action = ACTION_REJECT_PEER
            putExtra(EXTRA_AUTH_ADDRESS, request.deviceAddress)
        }
        val rejectPendingIntent = PendingIntent.getService(
            this,
            2,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, AUTH_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle(getString(com.rideflux.app.R.string.glasses_auth_notification_title))
            .setContentText(getString(com.rideflux.app.R.string.glasses_auth_notification_text, request.shortCode))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openAppPendingIntent)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(com.rideflux.app.R.string.action_deny),
                    rejectPendingIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(com.rideflux.app.R.string.action_allow),
                    approvePendingIntent,
                ).build(),
            )
            .build()

        manager.notify(AUTH_NOTIF_ID, notification)
    }

    private fun cancelAuthorizationNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(AUTH_NOTIF_ID)
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
                    getString(com.rideflux.app.R.string.notification_channel_bridge),
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
        val detail = getString(
            when (state) {
                BridgeState.STOPPED, BridgeState.STANDBY ->
                    com.rideflux.app.R.string.notification_bridge_standby
                BridgeState.ATTACHING -> com.rideflux.app.R.string.notification_bridge_attaching
                BridgeState.RELAYING -> com.rideflux.app.R.string.notification_bridge_relaying
                BridgeState.DEGRADED -> com.rideflux.app.R.string.notification_bridge_degraded
            },
        )
        return builder
            .setContentTitle(getString(com.rideflux.app.R.string.notification_bridge_title))
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "BridgeService"
        internal const val CHANNEL_ID = "rideflux_bridge"
        private const val NOTIF_ID = 7421

        const val EXTRA_MAC = "mac"
        const val EXTRA_FAMILY = "family"
        const val EXTRA_LINK_MODE = "link_mode"
        const val EXTRA_AUTH_ADDRESS = "auth_address"
        const val EXTRA_AUTH_TOKEN_HEX = "auth_token_hex"
        const val EXTRA_AUTH_SHORT_CODE = "auth_short_code"
        const val EXTRA_AUTH_IS_LEGACY = "auth_is_legacy"
        const val ACTION_START = "com.rideflux.app.bridge.START"
        const val ACTION_SET_TARGET = "com.rideflux.app.bridge.SET_TARGET"
        const val ACTION_CLEAR_TARGET = "com.rideflux.app.bridge.CLEAR_TARGET"
        const val ACTION_SET_LINK_MODE = "com.rideflux.app.bridge.SET_LINK_MODE"
        const val ACTION_APPROVE_PEER = "com.rideflux.app.bridge.APPROVE_PEER"
        const val ACTION_REJECT_PEER = "com.rideflux.app.bridge.REJECT_PEER"

        private const val AUTH_CHANNEL_ID = "rideflux_bridge_auth"
        private const val AUTH_NOTIF_ID = 7422

        private val _pendingAuthorization = MutableStateFlow<GlassesAuthorizationRequest?>(null)
        val pendingAuthorization: StateFlow<GlassesAuthorizationRequest?> = _pendingAuthorization.asStateFlow()

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

        private val linkModeSeeded = AtomicBoolean(false)

        /**
         * Aligns [linkMode] with the persisted preference once per
         * process.
         *
         * Without this the flow reports its declared default until the
         * service happens to run, so with the bridge stopped the UI
         * showed a transport the rider had not selected — and turning
         * the bridge on then started the stored one instead, silently
         * contradicting the chip they had just been looking at.
         */
        fun syncLinkMode(context: Context) {
            if (linkModeSeeded.compareAndSet(false, true)) {
                _linkMode.value = GlassesLinkPreferences.read(context)
            }
        }

        private val _linkState = MutableStateFlow(GlassesLinkState.STOPPED)
        val linkState: StateFlow<GlassesLinkState> = _linkState.asStateFlow()

        private val _hudVisible = MutableStateFlow(true)

        /**
         * Whether the glasses are being asked to show the HUD.
         *
         * Deliberately *not* persisted: a hidden HUD is a momentary
         * choice made mid-ride, and starting a later ride with a
         * blank display the rider does not remember switching off is
         * worse than making them press the ring again.
         */
        val hudVisible: StateFlow<Boolean> = _hudVisible.asStateFlow()

        /** Show or blank the HUD; takes effect on the next frame, which is immediate. */
        fun setHudVisible(visible: Boolean) {
            _hudVisible.value = visible
        }

        fun toggleHudVisible() {
            _hudVisible.value = !_hudVisible.value
        }

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

        fun approveGlasses(context: Context, request: GlassesAuthorizationRequest) {
            _pendingAuthorization.value = null
            launch(
                context,
                Intent(context, BridgeService::class.java).apply {
                    action = ACTION_APPROVE_PEER
                    putExtra(EXTRA_AUTH_ADDRESS, request.deviceAddress)
                    putExtra(EXTRA_AUTH_TOKEN_HEX, request.token?.let(BridgePairingToken::toHex))
                    putExtra(EXTRA_AUTH_SHORT_CODE, request.shortCode)
                    putExtra(EXTRA_AUTH_IS_LEGACY, request.isLegacy)
                },
            )
        }

        fun rejectGlasses(context: Context, request: GlassesAuthorizationRequest) {
            _pendingAuthorization.value = null
            launch(
                context,
                Intent(context, BridgeService::class.java).apply {
                    action = ACTION_REJECT_PEER
                    putExtra(EXTRA_AUTH_ADDRESS, request.deviceAddress)
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

/**
 * How many consecutive failed CXR opens force the bridge back to the
 * native BLE transport.
 *
 * One. `RokidCxrBridgePublisher.open()` now waits out a full connection
 * attempt before reporting failure, so a single false already means the
 * rider has spent ~20 s with a phone that advertises nothing at all —
 * there is nothing to gain by spending another minute proving it twice.
 */
private const val CXR_OPEN_FAILURE_LIMIT = 1

internal fun shouldDegradeFromCxr(consecutiveFailures: Int): Boolean =
    consecutiveFailures >= CXR_OPEN_FAILURE_LIMIT

internal fun reconnectBackoffMillis(attempt: Long): Long =
    (1_000L * (1L shl attempt.coerceIn(0L, 4L).toInt())).coerceAtMost(15_000L)

private class WheelLinkEnded : RuntimeException("wheel link ended")
