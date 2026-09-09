/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone-side BLE peripheral that advertises [BridgeProtocol.SERVICE_UUID]
 * and notifies subscribed centrals (the AR glasses) with one
 * [BridgeFrame] per emission of the source flow.
 *
 * Lifecycle:
 *  - [open] opens a [BluetoothGattServer], registers the bridge
 *    service, waits for the registration to be confirmed and only then
 *    starts BLE advertising.
 *  - [attachSource] independently observes (or replaces) the source
 *    flow without changing the server/advertising lifetime.
 *  - [stop] tears all of the above down. Idempotent.
 *
 * The wait in [open] is not incidental. `addService` completes
 * asynchronously through `onServiceAdded`; advertising before that
 * lands lets a fast central discover an *empty* GATT database, and
 * Android caches a peer's database by address. The central then sees a
 * bridge with no characteristics on every subsequent connection, and
 * the usual escape hatch — `BluetoothGatt.refresh()` — is a hidden API
 * blocked since Android 9. Ordering registration before advertising is
 * the only reliable fix.
 *
 * @param pairingToken identity published as service data in the scan
 *   response so the glasses can recognise this phone across BLE address
 *   rotation. See [BridgeProtocol.PAIRING_TOKEN_SIZE]. When null the
 *   server advertises exactly as before and only pre-token clients can
 *   match it.
 *
 * Threading: callbacks land on the system Binder thread. Internal
 * state ([subscribers]) uses [CopyOnWriteArraySet] for lock-free
 * fan-out from the Flow collector to all currently-subscribed
 * centrals. The class is fine to call from the main thread.
 *
 * Permissions required at the Manifest level (the caller's
 * responsibility): BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE.
 */
@SuppressLint("MissingPermission")
class BridgeServer(
    private val context: Context,
    private val pairingToken: ByteArray? = null,
    private val onSubscriberStateChanged: (Boolean) -> Unit = {},
) {

    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile private var telemetryChar: BluetoothGattCharacteristic? = null
    @Volatile private var collectionJob: Job? = null
    @Volatile private var advertiseLowLatency: Boolean = false

    // Registration of the GATT service is asynchronous; open() blocks
    // on this latch so advertising never precedes a usable database.
    @Volatile private var serviceAddedLatch: CountDownLatch? = null
    private val serviceRegistered = AtomicBoolean(false)

    // Advertising can fail asynchronously (onStartFailure) even after
    // startAdvertising() accepted the request. Track the outcome and
    // retry with capped backoff while the server stays open — without
    // this a failed advertise left the bridge permanently undiscoverable.
    private val advertiseStarted = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var advertiseRetryRunnable: Runnable? = null
    private var advertiseAttempts = 0

    /**
     * Centrals that have written a non-zero CCCD value for our
     * notify char. We notify exactly this set on each frame, so an
     * unsubscribed (or freshly-disconnected) client never causes a
     * spurious GATT write that would queue behind the next valid
     * notification.
     */
    private val subscribers = CopyOnWriteArraySet<BluetoothDevice>()
    private val subscriberConnected = AtomicBoolean(false)
    private val latestPayload = AtomicReference<ByteArray?>(null)
    private val notificationsInFlight = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val lastSubmittedPayload = ConcurrentHashMap<BluetoothDevice, ByteArray>()

    /**
     * Begin advertising. Safe to call multiple times — the first call
     * wins; subsequent calls are no-ops until [stop].
     *
     * Blocks for up to [SERVICE_ADD_TIMEOUT_MILLIS] waiting for the
     * GATT service registration to be confirmed, so call it off the
     * main thread. The wait happens outside this object's monitor, so a
     * concurrent [stop] is never delayed by it.
     */
    fun open(): Boolean {
        val latch = synchronized(this) {
            if (gattServer != null) return true
            beginOpenLocked()
        } ?: return false
        val registered = awaitServiceRegistration(latch)
        return synchronized(this) { finishOpenLocked(registered) }
    }

    /**
     * Open the GATT server and submit the service. Returns the latch
     * that [onServiceAdded] releases, or null if setup failed outright.
     */
    private fun beginOpenLocked(): CountDownLatch? {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: run {
                Log.w(TAG, "BluetoothManager unavailable; bridge not started")
                return null
            }

        // ---- GATT server setup ------------------------------------
        val char = BluetoothGattCharacteristic(
            BridgeProtocol.TELEMETRY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            // Permission bits for the *descriptor* below; the
            // characteristic itself is notify-only so no permissions
            // apply to it directly.
            0,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BridgeProtocol.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }

        val service = BluetoothGattService(
            BridgeProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).apply { addCharacteristic(char) }

        val server = mgr.openGattServer(context, serverCallback)
            ?: run {
                Log.e(TAG, "openGattServer returned null; bridge aborted")
                return null
            }

        val latch = CountDownLatch(1)
        serviceRegistered.set(false)
        serviceAddedLatch = latch
        if (!server.addService(service)) {
            // The service is absent from the GATT database; advertising
            // it anyway would let centrals find an unusable service.
            Log.e(TAG, "addService failed; aborting bridge startup")
            serviceAddedLatch = null
            server.close()
            return null
        }

        gattServer = server
        telemetryChar = char
        return latch
    }

    /**
     * Wait for `onServiceAdded`. Returns true only when the platform
     * confirmed the service is in the GATT database.
     */
    private fun awaitServiceRegistration(latch: CountDownLatch): Boolean = try {
        latch.await(SERVICE_ADD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) && serviceRegistered.get()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Log.w(TAG, "interrupted while waiting for GATT service registration", e)
        false
    }

    /**
     * Start advertising now that the service is registered, or tear the
     * half-built server back down.
     *
     * If advertising cannot start, the bridge is undiscoverable while
     * the GATT server stays open and the collector keeps consuming
     * frames — a half-started leak.
     */
    private fun finishOpenLocked(registered: Boolean): Boolean {
        // stop() may have run while open() was waiting off-monitor.
        if (gattServer == null) return false
        if (!registered) {
            Log.e(TAG, "GATT service registration not confirmed; aborting bridge startup")
            teardownServerLocked()
            return false
        }
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (mgr == null || !startAdvertising(mgr)) {
            Log.e(TAG, "advertising could not start; aborting bridge startup")
            teardownServerLocked()
            return false
        }
        return true
    }

    private fun teardownServerLocked() {
        try {
            gattServer?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "gattServer.close threw", t)
        }
        gattServer = null
        telemetryChar = null
        serviceAddedLatch = null
    }

    /**
     * Replace the frame source while leaving advertising and the GATT
     * server open. Source completion only ends the collector; the
     * owner explicitly controls the transport lifetime with [stop].
     */
    @Synchronized
    fun attachSource(scope: CoroutineScope, source: Flow<BridgeFrame>) {
        check(gattServer != null) { "BridgeServer.open() must succeed before attachSource()" }
        collectionJob?.cancel()
        collectionJob = source.onEach { frame ->
            val payload = try {
                BridgeCodec.encode(frame)
            } catch (t: Throwable) {
                // A malformed frame must not kill the whole pipeline:
                // log and skip it so subsequent frames keep flowing.
                Log.w(TAG, "encode failed; skipping frame", t)
                return@onEach
            }
            latestPayload.set(payload)
            // One notification at a time per central. Family-I2 can
            // produce ~40 frames/s; queuing every frame in Android's
            // GATT server makes the HUD show old speed samples. Keep
            // only the newest payload while a notification is in flight.
            for (dev in subscribers) {
                notifyLatest(dev)
            }
        }.launchIn(scope)
    }

    /** Compatibility wrapper for callers that still want one-shot setup. */
    fun start(scope: CoroutineScope, source: Flow<BridgeFrame>): Boolean {
        if (!open()) return false
        attachSource(scope, source)
        return true
    }

    /** Restarts only BLE advertising with the requested power/latency profile. */
    @Synchronized
    fun setAdvertiseMode(lowLatency: Boolean): Boolean {
        if (advertiseLowLatency == lowLatency) return true
        advertiseLowLatency = lowLatency
        if (gattServer == null) return true
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return false
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "stopAdvertising during mode switch threw", t)
        }
        advertiser = null
        advertiseStarted.set(false)
        cancelAdvertiseRetry()
        val started = startAdvertising(manager)
        if (!started) scheduleAdvertiseRetry()
        return started
    }

    /** Tear everything down. Safe to call when not started. */
    @Synchronized
    fun stop() {
        collectionJob?.cancel()
        collectionJob = null

        // Release a concurrent open() immediately instead of leaving it
        // to burn the full registration timeout.
        serviceAddedLatch?.countDown()
        serviceAddedLatch = null
        serviceRegistered.set(false)

        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "stopAdvertising threw", t)
        }
        advertiser = null
        advertiseStarted.set(false)
        advertiseAttempts = 0
        cancelAdvertiseRetry()

        try {
            gattServer?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "gattServer.close threw", t)
        }
        gattServer = null
        telemetryChar = null
        subscribers.clear()
        dispatchSubscriberState()
        latestPayload.set(null)
        notificationsInFlight.clear()
        lastSubmittedPayload.clear()
    }

    private fun notifyLatest(device: BluetoothDevice) {
        if (!subscribers.contains(device)) return
        val server = gattServer ?: return
        val characteristic = telemetryChar ?: return
        val payload = latestPayload.get() ?: return
        if (lastSubmittedPayload[device] === payload) return
        if (!notificationsInFlight.add(device)) return

        // Record before calling into the platform: vendor stacks are
        // allowed to dispatch onNotificationSent very quickly.
        lastSubmittedPayload[device] = payload
        val accepted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false,
                    payload,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed for ${device.address}: ${t.message}")
            false
        }

        if (!accepted) {
            notificationsInFlight.remove(device)
            lastSubmittedPayload.remove(device, payload)
        }
    }

    /** Returns true when advertising was successfully started. */
    private fun startAdvertising(mgr: BluetoothManager): Boolean {
        // The definitive outcome arrives via advertiseCallback; a new
        // attempt resets the flag so a stale onStartSuccess from a
        // previous session cannot mask a fresh failure.
        advertiseStarted.set(false)
        val adapter = mgr.adapter ?: run {
            Log.w(TAG, "Bluetooth adapter unavailable; skipping advertise")
            return false
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            Log.w(TAG, "Multi-advertise unsupported; advertise may still work but YMMV")
        }
        val adv = adapter.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "BluetoothLeAdvertiser unavailable; skipping advertise")
            return false
        }
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                if (advertiseLowLatency) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                else AdvertiseSettings.ADVERTISE_MODE_BALANCED,
            )
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        // Primary advertisement: flags (3 B) + the 128-bit service UUID
        // (18 B) = 21 of the 31 available bytes. No device name, and no
        // service data — 2 + 16 + 8 more bytes would overflow the PDU.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BridgeProtocol.SERVICE_UUID))
            .build()

        // The pairing token rides in the scan response, which has its
        // own 31-byte budget and no flags element. Android merges both
        // PDUs into one ScanRecord on the receiving side.
        val scanResponse = pairingToken?.let { token ->
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceData(ParcelUuid(BridgeProtocol.SERVICE_UUID), token)
                .build()
        }

        try {
            if (scanResponse == null) {
                adv.startAdvertising(settings, data, advertiseCallback)
            } else {
                adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startAdvertising threw", t)
            return false
        }
        return true
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertiseStarted.set(true)
            advertiseAttempts = 0
            Log.i(TAG, "advertise start ok: $settingsInEffect")
        }
        override fun onStartFailure(errorCode: Int) {
            advertiseStarted.set(false)
            Log.e(TAG, "advertise start failed: $errorCode")
            scheduleAdvertiseRetry()
        }
    }

    /**
     * Retries advertising with capped exponential backoff after an
     * asynchronous onStartFailure. No-ops once [stop] has torn the
     * server down; a successful start resets the attempt counter via
     * onStartSuccess.
     */
    @Synchronized
    private fun scheduleAdvertiseRetry() {
        advertiseRetryRunnable?.let(mainHandler::removeCallbacks)
        advertiseRetryRunnable = null
        if (gattServer == null) return
        val shift = advertiseAttempts.coerceIn(0, 5)
        val waitMs = (ADVERTISE_RETRY_BASE_MILLIS shl shift)
            .coerceAtMost(ADVERTISE_RETRY_MAX_MILLIS)
            .toLong()
        advertiseAttempts += 1
        Log.w(TAG, "retrying advertising in ${waitMs}ms (attempt $advertiseAttempts)")
        val runnable = Runnable {
            advertiseRetryRunnable = null
            if (gattServer == null || advertiseStarted.get()) return@Runnable
            val manager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    ?: return@Runnable
            Log.i(TAG, "advertise retry attempt")
            if (!startAdvertising(manager)) {
                scheduleAdvertiseRetry()
            }
        }
        advertiseRetryRunnable = runnable
        mainHandler.postDelayed(runnable, waitMs)
    }

    @Synchronized
    private fun cancelAdvertiseRetry() {
        advertiseRetryRunnable?.let(mainHandler::removeCallbacks)
        advertiseRetryRunnable = null
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid != BridgeProtocol.SERVICE_UUID) return
            val ok = status == BluetoothGatt.GATT_SUCCESS
            if (ok) {
                Log.i(TAG, "bridge GATT service registered")
            } else {
                Log.e(TAG, "bridge GATT service registration failed status=$status")
            }
            serviceRegistered.set(ok)
            // CountDownLatch is thread-safe; releasing it without taking
            // this object's monitor keeps the binder thread from ever
            // blocking behind open()/stop().
            serviceAddedLatch?.countDown()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "central ${device.address} status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribers.remove(device)
                dispatchSubscriberState()
                notificationsInFlight.remove(device)
                lastSubmittedPayload.remove(device)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notificationsInFlight.remove(device)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "notification to ${device.address} failed status=$status")
                lastSubmittedPayload.remove(device)
            }
            // If frames arrived while the previous packet was queued,
            // immediately submit only the newest one.
            notifyLatest(device)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            // The CCCD is readable (PERMISSION_READ), so a central
            // verifying subscription state expects a response. Reply
            // with the current value from our bookkeeping.
            val value = if (subscribers.contains(device)) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            // Honor the read offset: a partial read must return only
            // the remaining bytes from `offset` onward, not the whole
            // value from position 0.
            val response = if (offset >= value.size) {
                byteArrayOf()
            } else {
                value.copyOfRange(offset, value.size)
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == BridgeProtocol.CCCD_UUID) {
                // Prepared (long) writes are acknowledged but never
                // committed: there is no onExecuteWrite override, so a
                // central using a prepared write would receive success
                // yet never subscribe. Worse, the strict 2-byte check
                // below rejects the first fragment of a split prepared
                // write. Reject prepared writes explicitly instead.
                if (preparedWrite) {
                    Log.w(TAG, "rejecting prepared CCCD write from ${device.address}")
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                            offset,
                            null,
                        )
                    }
                    return
                }
                // Validate the 2-byte standard pattern: 0x01 0x00 =
                // notify on, 0x00 0x00 = off. Anything else is
                // malformed (single-byte writes, indicate-only 0x02,
                // etc.) and must be rejected rather than accepted.
                val valid = value.size == 2 && value[1].toInt() == 0 &&
                    (value[0].toInt() == 0 || value[0].toInt() == 1)
                if (!valid) {
                    Log.w(TAG, "malformed CCCD write ${device.address}: " +
                        value.joinToString { "%02x".format(it) })
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH,
                            offset,
                            null,
                        )
                    }
                    return
                }
                val enable = value[0].toInt() != 0
                if (enable) subscribers.add(device) else subscribers.remove(device)
                dispatchSubscriberState()
                Log.i(TAG, "CCCD write ${device.address} enable=$enable")
            }
            // Acknowledge the write before notifying anything so the
            // central never sees a notification for an unacknowledged
            // subscription change.
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    null,
                )
            }
            if (descriptor.uuid == BridgeProtocol.CCCD_UUID && subscribers.contains(device)) {
                notifyLatest(device)
            }
        }
    }

    private fun dispatchSubscriberState() {
        val connected = subscribers.isNotEmpty()
        if (subscriberConnected.getAndSet(connected) == connected) return
        runCatching { onSubscriberStateChanged(connected) }
            .onFailure { Log.w(TAG, "subscriber-state callback failed", it) }
    }

    private companion object {
        const val TAG = "BridgeServer"
        const val ADVERTISE_RETRY_BASE_MILLIS = 2_000
        const val ADVERTISE_RETRY_MAX_MILLIS = 30_000

        /**
         * How long [open] waits for `onServiceAdded`. The platform
         * normally answers within milliseconds; this only bounds a
         * wedged Bluetooth stack so the caller's retry loop can act.
         */
        const val SERVICE_ADD_TIMEOUT_MILLIS = 5_000L
    }
}
