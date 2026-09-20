/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Base64
import android.util.Log
import com.rideflux.app.BuildConfig
import com.rideflux.data.bridge.BridgeCodec
import com.rideflux.data.bridge.BridgeFrame
import com.rideflux.data.bridge.BridgeProtocol
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone-side publisher backed by Rokid's official CXR-M SDK.
 *
 * The RV101 consumer image exposes the CXR service over an already-bonded
 * classic-Bluetooth device. Telemetry keeps the same compact BridgeCodec
 * payload as native BLE, so both transports are bit-for-bit equivalent.
 */
@SuppressLint("MissingPermission")
internal class RokidCxrBridgePublisher(
    private val context: Context,
    parentScope: CoroutineScope,
    private val onState: (GlassesLinkState) -> Unit,
    private val preferredGlassesMacProvider: (() -> String?)? = null,
) : BridgePublisher {

    private val publisherJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + publisherJob)
    private val latestPayload = AtomicReference<ByteArray?>(null)
    @Volatile private var collectionJob: Job? = null
    @Volatile private var connectJob: Job? = null
    @Volatile private var connectTimeoutJob: Job? = null
    @Volatile private var reconnectJob: Job? = null
    /** Resolved by the first connect's outcome; see [open]. */
    @Volatile private var firstAttempt: CompletableDeferred<Boolean>? = null
    private var reconnectAttempt = 0L

    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var reinitializing = false
    @Volatile private var targetDevice: BluetoothDevice? = null

    private val api: CxrApi by lazy { CxrApi.getInstance() }
    private val messageBridge: CXRServiceBridge by lazy { CXRServiceBridge() }

    private val callback = object : BluetoothStatusCallback {
        override fun onConnectionInfo(
            socketUuid: String?,
            macAddress: String?,
            rokidAccount: String?,
            glassesType: Int,
        ) {
            if (!running) return
            if (socketUuid.isNullOrBlank() || macAddress.isNullOrBlank()) {
                Log.w(TAG, "CXR returned incomplete connection info")
                handleConnectionFailure("incomplete connection info")
                return
            }
            try {
                // client-m 1.0.4 silently returns when either auth argument
                // is null. Empty values are intentional for consumer RV101
                // firmware; provisioned units receive the configured values.
                api.connectBluetooth(
                    context,
                    socketUuid,
                    macAddress,
                    this,
                    decodedSnAuth(),
                    BuildConfig.ROKID_CLIENT_SECRET,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "CXR final connect failed: ${t.message}")
                handleConnectionFailure("final connect threw")
            }
        }

        override fun onConnected() {
            handleConnected()
        }

        override fun onDisconnected() {
            if (!running) return
            if (reinitializing) {
                Log.d(TAG, "ignoring expected disconnect during CXR reinitialization")
                return
            }
            connected = false
            onState(GlassesLinkState.STARTING)
            scheduleReconnect("disconnected")
        }

        override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
            if (!running) return
            connected = false
            Log.w(TAG, "Rokid CXR connection failed: $errorCode")
            handleConnectionFailure("SDK failure $errorCode")
        }
    }

    /**
     * Opens the CXR transport and **waits for the first connection
     * attempt to resolve**.
     *
     * Returning true as soon as a bonded device was found made the
     * caller's fallback unreachable: the real connect is asynchronous,
     * and every failure was absorbed by this class's own retry loop, so
     * [BridgeService]'s degrade-to-BLE counter — which only advances
     * when `open()` returns false — never moved. On consumer glasses
     * that meant looping on 20 s timeouts forever while the phone
     * advertised nothing for the HUD to find.
     */
    override suspend fun open(): Boolean {
        val pending = beginOpen() ?: return false
        // armConnectionTimeout always resolves `pending`; the outer
        // bound only covers an SDK that never calls back at all.
        val connected = withTimeoutOrNull(CXR_OPEN_TIMEOUT_MILLIS) { pending.await() } ?: false
        if (!connected) {
            Log.w(TAG, "CXR did not connect on the first attempt; caller may degrade")
        }
        return connected
    }

    /**
     * Synchronous half of [open]: claims the transport and fires the
     * first connect. Returns the deferred that the SDK callbacks (or the
     * connection timeout) resolve, or null when there is nothing to
     * connect to. open() and stop() mutate the same `running` /
     * `targetDevice` state and must not interleave, hence the monitor —
     * the await deliberately happens outside it.
     */
    @Synchronized
    private fun beginOpen(): CompletableDeferred<Boolean>? {
        if (running) return firstAttempt ?: CompletableDeferred(connected)
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
        val device = adapter?.let(::findBondedGlasses)
        if (device == null) {
            Log.w(TAG, "No bonded Rokid glasses found; pair RV101 in Android settings first")
            onState(GlassesLinkState.ERROR)
            return null
        }
        targetDevice = device
        running = true
        reconnectAttempt = 0L
        val pending = CompletableDeferred<Boolean>()
        firstAttempt = pending
        onState(GlassesLinkState.STARTING)
        connectOnce()
        return pending
    }

    @OptIn(FlowPreview::class)
    override fun attachSource(scope: CoroutineScope, source: Flow<BridgeFrame>) {
        collectionJob?.cancel()
        // Family-I2 can update at 40 Hz. A 20 Hz latest-frame stream is
        // visually immediate on the 60 Hz HUD while preventing a stale
        // queue from building inside the CXR socket.
        collectionJob = this.scope.launch {
            source
                .sample(CXR_FRAME_PERIOD_MILLIS)
                .catch { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "CXR frame source ended: ${error.message}")
                }
                .collect { frame ->
                    val payload = try {
                        BridgeCodec.encode(frame)
                    } catch (t: Throwable) {
                        Log.w(TAG, "dropping unencodable CXR frame: ${t.message}")
                        return@collect
                    }
                    latestPayload.set(payload)
                    if (connected) sendPayload(payload)
                }
        }
    }

    @Synchronized
    override fun stop() {
        running = false
        connected = false
        reinitializing = false
        // Release an open() still waiting on the first attempt; without
        // this it would burn the full CXR_OPEN_TIMEOUT_MILLIS after a
        // mode switch has already torn this publisher down.
        firstAttempt?.complete(false)
        firstAttempt = null
        connectJob?.cancel()
        connectTimeoutJob?.cancel()
        reconnectJob?.cancel()
        collectionJob?.cancel()
        connectJob = null
        connectTimeoutJob = null
        reconnectJob = null
        runCatching { api.deinitBluetooth() }
        publisherJob.cancel()
        scope.cancel()
        onState(GlassesLinkState.STOPPED)
    }

    @Synchronized
    private fun connectOnce() {
        if (!running || connectJob?.isActive == true) return
        val device = targetDevice ?: return
        reconnectJob?.cancel()
        reconnectJob = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                connected = false
                onState(GlassesLinkState.STARTING)
                reinitializing = true
                runCatching { api.deinitBluetooth() }
                delay(CXR_REINIT_SETTLE_MILLIS)
                if (!running) return@launch
                armConnectionTimeout()
                api.initBluetooth(context, device, callback)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG, "CXR init failed: ${t.message}")
                handleConnectionFailure("init threw")
            } finally {
                reinitializing = false
            }
        }
        connectJob = job
        job.start()
    }

    @Synchronized
    private fun armConnectionTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CXR_CONNECT_TIMEOUT_MILLIS)
            connectTimeoutJob = null
            if (!running || connected) return@launch

            // Some SDK/firmware combinations reach the socket without
            // delivering onConnected. Recover that state before tearing
            // down an otherwise healthy connection.
            val sdkConnected = runCatching { api.isBluetoothConnected }
                .getOrDefault(false)
            if (sdkConnected) {
                callback.onConnected()
            } else {
                Log.w(TAG, "CXR connection timed out")
                handleConnectionFailure("connection timeout")
            }
        }
    }

    private fun handleConnectionFailure(reason: String) {
        connected = false
        onState(GlassesLinkState.ERROR)
        // Until open() has returned, the retry decision belongs to the
        // caller: resolving false lets it fall back to the native BLE
        // bridge instead of leaving this publisher looping on a
        // transport that is not going to work on this hardware. Once a
        // link has been established, a later drop is ours to heal.
        val pending = firstAttempt
        if (pending != null && !pending.isCompleted) {
            Log.w(TAG, "CXR first attempt failed: $reason")
            pending.complete(false)
            return
        }
        scheduleReconnect(reason)
    }

    @Synchronized
    private fun handleConnected() {
        if (!running) return
        connected = true
        reconnectAttempt = 0L
        firstAttempt?.complete(true)
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        onState(GlassesLinkState.CONNECTED)
        latestPayload.get()?.let(::sendPayload)
        Log.i(TAG, "Rokid CXR connected")
    }

    @Synchronized
    private fun scheduleReconnect(reason: String) {
        if (!running || reconnectJob?.isActive == true) return
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        val waitMs = cxrReconnectBackoffMillis(reconnectAttempt++)
        Log.w(TAG, "CXR $reason; retrying in ${waitMs}ms")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(waitMs)
            synchronized(this@RokidCxrBridgePublisher) {
                reconnectJob = null
            }
            connectOnce()
        }
        reconnectJob = job
        job.start()
    }

    private fun sendPayload(payload: ByteArray) {
        try {
            val args = Caps().apply { writeInt32(BridgeProtocol.PROTOCOL_VERSION.toInt()) }
            val result = messageBridge.sendMessage(
                BridgeProtocol.CXR_TELEMETRY_CHANNEL,
                args,
                payload,
            )
            if (result != 0) Log.w(TAG, "CXR telemetry send returned $result")
        } catch (t: Throwable) {
            Log.w(TAG, "CXR telemetry send failed: ${t.message}")
        }
    }

    private fun decodedSnAuth(): ByteArray = BuildConfig.ROKID_SN_AUTH_BASE64
        .takeIf { it.isNotBlank() }
        ?.let { encoded -> runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() }
        ?.takeIf { it.isNotEmpty() }
        ?: ByteArray(0)

    private fun findBondedGlasses(adapter: BluetoothAdapter): BluetoothDevice? {
        val devices = adapter.bondedDevices.toList()
        val pairs = devices.map { it.address to runCatching { it.name.orEmpty() }.getOrDefault("") }
        val selectedAddress = selectBondedGlasses(pairs, preferredGlassesMacProvider?.invoke())
        return devices.firstOrNull { it.address.equals(selectedAddress, ignoreCase = true) }
    }

    private companion object {
        const val TAG = "RokidCxrPublisher"
        const val CXR_FRAME_PERIOD_MILLIS = 50L
        const val CXR_REINIT_SETTLE_MILLIS = 300L
        const val CXR_CONNECT_TIMEOUT_MILLIS = 20_000L

        /**
         * Upper bound on [open]. Comfortably exceeds
         * [CXR_CONNECT_TIMEOUT_MILLIS] plus the re-init settle, so it
         * only fires when the SDK delivers no callback whatsoever.
         */
        const val CXR_OPEN_TIMEOUT_MILLIS = 25_000L
    }
}

internal fun cxrReconnectBackoffMillis(attempt: Long): Long =
    (1_000L * (1L shl attempt.coerceIn(0L, 4L).toInt())).coerceAtMost(15_000L)

internal fun selectBondedGlasses(
    devices: List<Pair<String, String>>,
    preferredMac: String?,
): String? {
    val preferred = preferredMac?.trim()?.uppercase()
    if (!preferred.isNullOrEmpty()) {
        val match = devices.firstOrNull { it.first.equals(preferred, ignoreCase = true) }
        if (match != null) return match.first
    }
    return devices
        .sortedByDescending { (_, name) ->
            when {
                name.contains("rokid", ignoreCase = true) -> 2
                name.contains("glass", ignoreCase = true) -> 1
                else -> 0
            }
        }
        .firstOrNull { (_, name) ->
            name.contains("rokid", ignoreCase = true) ||
                name.contains("glass", ignoreCase = true)
        }
        ?.first
}

