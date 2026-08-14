/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rideflux.domain.transport.BleTransport
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [BleTransport] backed by the platform `android.bluetooth.BluetoothGatt`
 * API, driven by coroutines.
 *
 * ### GATT topology
 * The transport is instantiated with a [GattTopology] resolved from
 * the advertisement (see [WheelCodecFactoryImpl.topologyFor] /
 * [WheelCodecFactoryImpl.inferFromAdvertisement]). Once the platform
 * finishes service discovery, the correct notify / write
 * characteristics are looked up per §1.1 / §1.2 — falling back to the
 * topology the wheel actually exposes if the advertisement-derived
 * guess turns out to be wrong (see [resolveCharacteristics]).
 *
 * ### Threading model
 * Every GATT operation on Android is callback-driven and the stack
 * rejects concurrent requests on a single GATT instance. All
 * `suspend` methods therefore acquire [opMutex] and suspend on a
 * [CompletableDeferred] completed by the matching callback method.
 *
 * Callbacks fire on the platform's binder thread; we re-dispatch every
 * emission to [scope] so consumers see bytes on a predictable
 * dispatcher.
 *
 * ### Permissions
 * The Android manifest / runtime permission dance
 * (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `ACCESS_FINE_LOCATION`) is
 * the responsibility of the `:app` module. This class assumes
 * permissions have been granted before [connect] is called.
 */
@SuppressLint("MissingPermission")
class AndroidBleTransport internal constructor(
    private val context: Context,
    private val device: BluetoothDevice,
    private val topology: GattTopology,
    private val scope: CoroutineScope,
) : BleTransport {

    // ---- Public flow ---------------------------------------------------

    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    // ---- GATT state (single-writer via opMutex) ------------------------

    private val opMutex = Mutex()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var notifyChar: BluetoothGattCharacteristic? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var connected: Boolean = false
    @Volatile private var closed: Boolean = false

    // Continuations completed by GATT callbacks. @Volatile: these are
    // written from coroutines (connect/disconnect/write) and read on the
    // binder callback thread, so without a happens-before edge a
    // callback can observe a stale null and skip the resume — hanging
    // the operation forever.
    @Volatile private var connectCont: CancellableContinuation<Unit>? = null
    @Volatile private var disconnectCont: CancellableContinuation<Unit>? = null

    /**
     * A submitted write awaiting its `onCharacteristicWrite` callback,
     * paired with the watchdog runnable that fails it.
     *
     * The runnable is carried alongside the continuation so that a
     * *successful* write can cancel it: otherwise every write left a
     * dead Runnable sitting in the main looper queue for the full
     * timeout window.
     */
    private class PendingWrite(
        val cont: CancellableContinuation<Unit>,
        @Volatile var timeout: Runnable? = null,
    )

    private val writeQueue = ConcurrentLinkedDeque<PendingWrite>()

    /** Post-connect settle runnable; cancelled if the link drops first. */
    private var connectSettle: Runnable? = null

    // Number of additional connectGatt() attempts already burned on
    // status=133 (GATT_ERROR) disconnects during the current connect
    // request. Reset every time connect() is freshly called.
    @Volatile private var connectRetriesUsed: Int = 0

    /** Number of discoverServices() retries already burned for the current connect attempt. */
    @Volatile private var discoverRetriesUsed: Int = 0

    /** Watchdog set after discoverServices() — fires if onServicesDiscovered never arrives. */
    private var discoverWatchdog: Runnable? = null

    /** Whether discoverServices() has been issued and is awaiting its callback. */
    @Volatile private var discoveryInFlight: Boolean = false

    /** Fallback runnable that forces discovery if onMtuChanged never arrives. */
    private var mtuFallback: Runnable? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // Ignore late callbacks from a stale (closed/replaced) GATT
            // instance so they can't corrupt a newer connection.
            if (g !== gatt) return
            Log.i(TAG, "onConnectionStateChange addr=${device.address} status=$status newState=$newState topology=$topology")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // Begode/Gotway boards on Xiaomi/MIUI have a stack quirk where
                        // discoverServices() returns true but onServicesDiscovered never
                        // fires — the wheel then drops the link ~6s later. Requesting an
                        // MTU exchange first forces the wheel's BLE chip out of the
                        // half-paired state, after which discovery works reliably.
                        discoverRetriesUsed = 0
                        Log.i(TAG, "connected; bondState=${device.bondState} — requesting MTU=$REQUESTED_MTU")
                        // Store and guard this runnable. Previously it was
                        // anonymous and uncancellable, so after a status=133
                        // disconnect (old GATT closed, fresh one created by
                        // the retry) it still fired against the stale
                        // instance: a false discoverServices() then failed
                        // the *retried* connection's continuation, and a
                        // true one set discoveryInFlight so the new
                        // connection's onMtuChanged skipped discovery and
                        // hung until the watchdog.
                        cancelConnectSettle()
                        val settle = Runnable {
                            if (closed || g !== gatt) return@Runnable
                            try {
                                val ok = g.requestMtu(REQUESTED_MTU)
                                Log.i(TAG, "requestMtu($REQUESTED_MTU) -> $ok")
                                if (!ok) {
                                    // Fall back to bare discovery if requestMtu was rejected.
                                    startDiscovery(g, refreshFirst = false)
                                } else {
                                    // Arm a fallback in case onMtuChanged never arrives.
                                    armMtuFallback(g)
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "requestMtu threw: ${t.message} — falling back to discovery")
                                startDiscovery(g, refreshFirst = false)
                            }
                        }
                        connectSettle = settle
                        mainHandler.postDelayed(settle, POST_CONNECT_SETTLE_MILLIS)
                    } else {
                        failConnect(IOException("onConnectionStateChange: GATT status=$status"))
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    cancelDiscoverWatchdog()
                    cancelMtuFallback()
                    cancelConnectSettle()
                    // Clear the in-flight flag: a disconnect mid-discovery
                    // (e.g. status=133) would otherwise leave it set, so
                    // the retried connection's onMtuChanged skips
                    // startDiscovery() and connect() hangs forever.
                    discoveryInFlight = false
                    Log.w(TAG, "GATT disconnected addr=${device.address} status=$status connected=$connected")
                    connected = false
                    // Resume a pending disconnect, if any.
                    disconnectCont?.takeIf { it.isActive }?.resume(Unit)
                    disconnectCont = null

                    // Status 133 (GATT_ERROR / 0x85) is the BT stack's
                    // generic "connection attempt failed" code and is
                    // commonly seen on Begode / Gotway boards when the
                    // wheel BT chip is in a half-paired state. A fresh
                    // connectGatt() after a short close+settle window
                    // almost always succeeds, so retry transparently
                    // up to MAX_CONNECT_RETRIES times before surfacing
                    // the failure to the caller.
                    val pending = connectCont
                    val retrying = pending != null &&
                        pending.isActive &&
                        !closed &&
                        status == GATT_ERROR_133 &&
                        connectRetriesUsed < MAX_CONNECT_RETRIES
                    if (retrying) {
                        connectRetriesUsed += 1
                        Log.w(
                            TAG,
                            "status=133 during connect — retry " +
                                "$connectRetriesUsed/$MAX_CONNECT_RETRIES in " +
                                "${RECONNECT_DELAY_MILLIS}ms",
                        )
                        try { g.close() } catch (_: Throwable) { /* best-effort */ }
                        gatt = null
                        drainWriteQueueWithError(IOException("link lost during write"))
                        mainHandler.postDelayed(
                            { attemptConnectGattRetry() },
                            RECONNECT_DELAY_MILLIS,
                        )
                        return
                    }

                    // Fail any pending connect / writes that never finished.
                    failConnect(IOException("link lost during connect (status=$status)"))
                    drainWriteQueueWithError(IOException("link lost during write"))
                    try { g.close() } catch (_: Throwable) { /* best-effort */ }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !== gatt || closed) return
            cancelMtuFallback()
            Log.i(TAG, "onMtuChanged mtu=$mtu status=$status — starting service discovery")
            // Only start discovery if none is already in flight (the
            // MTU fallback may already have kicked one off).
            if (!discoveryInFlight) startDiscovery(g, refreshFirst = false)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            discoveryInFlight = false
            cancelDiscoverWatchdog()
            Log.i(TAG, "onServicesDiscovered status=$status serviceCount=${g.services?.size}")
            // Dump every service+characteristic so we can confirm which UUIDs the wheel actually exposes.
            g.services?.forEach { svc ->
                val chars = svc.characteristics?.joinToString { it.uuid.toString() } ?: ""
                Log.i(TAG, "  service=${svc.uuid} chars=[$chars]")
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnect(IOException("onServicesDiscovered status=$status"))
                return
            }
            val resolved = resolveCharacteristics(g, topology)
            if (resolved == null) {
                Log.e(TAG, "resolveCharacteristics returned null for topology=$topology -- wheel does not expose the expected service/characteristic UUIDs")
                failConnect(IOException("required GATT characteristics missing for topology=$topology"))
                return
            }
            Log.i(TAG, "resolved notify=${resolved.notify.uuid} write=${resolved.write.uuid}")
            notifyChar = resolved.notify
            writeChar = resolved.write

            if (!g.setCharacteristicNotification(resolved.notify, true)) {
                failConnect(IOException("setCharacteristicNotification returned false"))
                return
            }
            val ccc = resolved.notify.getDescriptor(GattUuids.DESCRIPTOR_CCC)
            if (ccc == null) {
                Log.e(TAG, "CCC descriptor missing on notify char ${resolved.notify.uuid}")
                failConnect(IOException("CCC descriptor missing on notify char ${resolved.notify.uuid}"))
                return
            }
            writeCccDescriptor(g, ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            // Guard like the other callbacks: a late callback from a
            // stale/closed GATT instance must not set connected = true
            // or resume a newer connection's continuation.
            if (g !== gatt || closed) return
            if (d.uuid != GattUuids.DESCRIPTOR_CCC) return
            Log.i(TAG, "CCC write status=$status -- notifications ${if (status == 0) "ENABLED" else "FAILED"}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                connectCont?.takeIf { it.isActive }?.resume(Unit)
                connectCont = null
            } else {
                failConnect(IOException("CCC write failed status=$status"))
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
        ) {
            // Legacy API, still invoked on API < 33 and as a fallback.
            val value = c.value ?: return
            Log.v(TAG, "notify(legacy) ${c.uuid} len=${value.size}")
            dispatchIncoming(value.copyOf())
        }

        // API 33+ overload carries the value directly.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            Log.v(TAG, "notify ${c.uuid} len=${value.size}")
            dispatchIncoming(value.copyOf())
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (g !== gatt || closed) return
            // Pop cancelled entries in a loop: write() deliberately
            // leaves cancelled continuations in the queue, so if the
            // stack delivers only a later write's callback, polling
            // once and returning early would leave that write suspended
            // forever while holding opMutex (wedging all later writes).
            var next = writeQueue.pollFirst()
            while (next != null && !next.cont.isActive) {
                next.timeout?.let(mainHandler::removeCallbacks)
                next = writeQueue.pollFirst()
            }
            if (next == null) return
            // Drop the watchdog now that the callback has arrived —
            // otherwise a successful write leaves a dead Runnable in the
            // main looper queue for the whole timeout window.
            next.timeout?.let(mainHandler::removeCallbacks)
            next.timeout = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                next.cont.resume(Unit)
            } else {
                next.cont.resumeWithException(IOException("write failed status=$status"))
            }
        }
    }

    // ---- Public API ----------------------------------------------------

    override suspend fun connect() {
        opMutex.withLock {
            check(!closed) { "transport has been closed" }
            if (connected) return
            connectRetriesUsed = 0
            discoveryInFlight = false
            suspendCancellableCoroutine<Unit> { cont ->
                connectCont = cont
                cont.invokeOnCancellation {
                    connectCont = null
                    // A cancelled connect must not leak the BluetoothGatt:
                    // close it so its callbacks stop firing into stale
                    // state and the link is actually torn down.
                    cancelDiscoverWatchdog()
                    cancelMtuFallback()
                    cancelConnectSettle()
                    discoveryInFlight = false
                    try { gatt?.close() } catch (_: Throwable) { /* best-effort */ }
                    gatt = null
                }
                try {
                    // Create into a local first. If the coroutine is
                    // cancelled while connectGatt() is executing, the
                    // cancellation handler above closes whatever `gatt`
                    // held at that moment (null) — publishing the fresh
                    // instance afterwards would leak a live link whose
                    // callbacks fire into already-nulled state. Publish
                    // only while the continuation is still active.
                    val newGatt: BluetoothGatt? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            device.connectGatt(
                                context,
                                /* autoConnect = */ false,
                                callback,
                                BluetoothDevice.TRANSPORT_LE,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            device.connectGatt(context, false, callback)
                        }
                    when {
                        !cont.isActive -> {
                            try {
                                newGatt?.close()
                            } catch (_: Throwable) { /* best-effort */ }
                        }
                        newGatt == null -> {
                            connectCont = null
                            cont.resumeWithException(IOException("connectGatt returned null"))
                        }
                        else -> gatt = newGatt
                    }
                } catch (t: Throwable) {
                    connectCont = null
                    cont.resumeWithException(t)
                }
            }
        }
    }

    override suspend fun disconnect() {
        // NOTE: disconnect() marks the transport as permanently closed —
        // this instance is single-use. A later connect() throws
        // "transport has been closed". Reconnects must allocate a fresh
        // transport (as WheelRepositoryImpl does per connection entry).
        opMutex.withLock {
            if (closed) return
            closed = true
            val g = gatt ?: run {
                connected = false
                return
            }
            if (!connected) {
                try { g.close() } catch (_: Throwable) { /* best-effort */ }
                gatt = null
                return
            }
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    disconnectCont = cont
                    cont.invokeOnCancellation { disconnectCont = null }
                    try { g.disconnect() } catch (t: Throwable) {
                        disconnectCont = null
                        cont.resumeWithException(t)
                    }
                }
            } finally {
                try { g.close() } catch (_: Throwable) { /* best-effort */ }
                gatt = null
                connected = false
            }
        }
    }

    override suspend fun write(bytes: ByteArray) {
        opMutex.withLock {
            val g = gatt ?: throw IOException("not connected")
            val ch = writeChar ?: throw IOException("write characteristic not resolved")
            if (!connected) throw IOException("not connected")
            Log.d(TAG, "write ${bytes.size}B -> ${ch.uuid}")
            suspendCancellableCoroutine<Unit> { cont ->
                val pending = PendingWrite(cont)
                writeQueue.addLast(pending)
                // Watchdog: if onCharacteristicWrite never fires (silent
                // peer / flaky stack), the head continuation would
                // suspend forever while holding opMutex — also blocking
                // disconnect() and every later write. Fail it instead.
                val timeout = Runnable {
                    if (writeQueue.remove(pending)) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IOException("write timed out after ${WRITE_TIMEOUT_MILLIS}ms"),
                            )
                        }
                        // The stack may still deliver
                        // onCharacteristicWrite for this write later. With
                        // the entry gone from the queue, that late callback
                        // would pop and resume the NEXT write with a status
                        // it never received, while that write had already
                        // been submitted to a stack that hadn't finished
                        // this one — a corrupted write pipeline. Invalidate
                        // the link so the stale callback is filtered by the
                        // `g !== gatt` guard in every callback.
                        Log.w(TAG, "write timeout — invalidating GATT link to drop stale callbacks")
                        connected = false
                        try { gatt?.close() } catch (_: Throwable) { /* best-effort */ }
                        gatt = null
                        drainWriteQueueWithError(
                            IOException("link invalidated after write timeout"),
                        )
                    }
                }
                pending.timeout = timeout
                mainHandler.postDelayed(timeout, WRITE_TIMEOUT_MILLIS)
                cont.invokeOnCancellation { mainHandler.removeCallbacks(timeout) }
                // Deliberately do NOT remove the continuation from the
                // queue on cancellation: the GATT stack will still
                // deliver onCharacteristicWrite for a submitted write,
                // and that callback pops the queue and resumes the
                // *next* entry. Removing on cancel would let a later
                // write be resumed prematurely with a status it never
                // received. The existing loop in onCharacteristicWrite
                // skips cancelled continuations, keeping queue order
                // consistent with GATT callback order.
                try {
                    val ok = submitWrite(g, ch, bytes)
                    if (!ok) {
                        writeQueue.remove(pending)
                        mainHandler.removeCallbacks(timeout)
                        cont.resumeWithException(IOException("writeCharacteristic returned false"))
                    }
                } catch (t: Throwable) {
                    writeQueue.remove(pending)
                    mainHandler.removeCallbacks(timeout)
                    cont.resumeWithException(t)
                }
            }
        }
    }

    // ---- Helpers -------------------------------------------------------

    private fun dispatchIncoming(bytes: ByteArray) {
        // No UNDISPATCHED: it runs the body synchronously on the binder
        // callback thread until the first real suspension, and because
        // _incoming is a DROP_OLDEST SharedFlow with spare capacity,
        // emit() effectively never suspends — so downstream collectors
        // would execute on the binder thread, contradicting this class's
        // documented "emissions are re-dispatched" contract.
        scope.launch(Dispatchers.Default) {
            try {
                _incoming.emit(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A throwing downstream collector must not escape this
                // launch: depending on the injected scope's job type it
                // would cancel the whole transport scope, killing
                // connect/write/disconnect.
                Log.w(TAG, "incoming collector failed: ${t.message}", t)
            }
        }
    }

    private fun failConnect(cause: Throwable) {
        val cont = connectCont
        if (cont != null && cont.isActive) {
            connectCont = null
            cont.resumeWithException(cause)
        }
        // A failed connect must not leave the BluetoothGatt open: the
        // leaked instance keeps its callbacks firing (potentially into
        // a newer connection's state) and holds a live link to the peer.
        // Closing here is harmless if the GATT was already closed (the
        // DISCONNECTED path double-close is tolerated by the stack).
        cancelDiscoverWatchdog()
        cancelMtuFallback()
        discoveryInFlight = false
        try { gatt?.close() } catch (_: Throwable) { /* best-effort */ }
        gatt = null
    }

    private fun drainWriteQueueWithError(cause: Throwable) {
        while (true) {
            val next = writeQueue.pollFirst() ?: return
            next.timeout?.let(mainHandler::removeCallbacks)
            next.timeout = null
            if (next.cont.isActive) next.cont.resumeWithException(cause)
        }
    }

    /** Cancel the pending post-connect settle runnable, if any. */
    private fun cancelConnectSettle() {
        val r = connectSettle ?: return
        mainHandler.removeCallbacks(r)
        connectSettle = null
    }

    /** Re-issue connectGatt() on the main thread after a status=133 settle delay. */
    private fun attemptConnectGattRetry() {
        if (closed) return
        val cont = connectCont
        if (cont == null || !cont.isActive) return
        try {
            val g: BluetoothGatt? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(
                    context,
                    /* autoConnect = */ false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                )
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, callback)
            }
            gatt = g
            if (g == null) {
                failConnect(IOException("connectGatt returned null on retry"))
            }
        } catch (t: Throwable) {
            failConnect(t)
        }
    }

    /**
     * Issue discoverServices() with an optional cache refresh, and arm a
     * watchdog that retries (refresh+discover) once if onServicesDiscovered
     * never fires. This guards against a Xiaomi/MIUI race where
     * refresh()+discoverServices() in the same dispatch frame causes the
     * discovery callback to silently never arrive — see field log
     * 2026-… (status=0 newState=0 peer-disconnect ~8s after discoverServices).
     */
    private fun startDiscovery(g: BluetoothGatt, refreshFirst: Boolean) {
        if (closed) return
        if (discoveryInFlight) return
        if (refreshFirst) {
            val refreshed = refreshGattCache(g)
            Log.i(TAG, "GATT cache refresh -> $refreshed")
        }
        try {
            val ok = g.discoverServices()
            Log.i(TAG, "discoverServices() requested -> $ok (refreshFirst=$refreshFirst attempt=${discoverRetriesUsed + 1})")
            if (!ok) {
                failConnect(IOException("discoverServices returned false"))
                return
            }
            discoveryInFlight = true
        } catch (t: Throwable) {
            failConnect(t); return
        }
        armDiscoverWatchdog(g)
    }

    private fun armDiscoverWatchdog(g: BluetoothGatt) {
        cancelDiscoverWatchdog()
        val r = Runnable {
            if (closed) return@Runnable
            val cont = connectCont
            if (cont == null || !cont.isActive) return@Runnable
            if (discoverRetriesUsed >= MAX_DISCOVER_RETRIES) {
                Log.e(TAG, "discoverServices watchdog: no callback after ${DISCOVER_WATCHDOG_MILLIS}ms and retries exhausted")
                failConnect(IOException("onServicesDiscovered never fired"))
                return@Runnable
            }
            discoverRetriesUsed += 1
            Log.w(TAG, "discoverServices watchdog: no callback after ${DISCOVER_WATCHDOG_MILLIS}ms — refresh+retry ($discoverRetriesUsed/$MAX_DISCOVER_RETRIES)")
            // Clear the in-flight flag so the retry actually re-issues
            // discoverServices(): startDiscovery() returns immediately
            // while discoveryInFlight is set, which made this watchdog
            // a no-op for the exact lost-callback race it guards.
            discoveryInFlight = false
            startDiscovery(g, refreshFirst = true)
        }
        discoverWatchdog = r
        mainHandler.postDelayed(r, DISCOVER_WATCHDOG_MILLIS)
    }

    private fun cancelDiscoverWatchdog() {
        val r = discoverWatchdog ?: return
        mainHandler.removeCallbacks(r)
        discoverWatchdog = null
    }

    /**
     * If [BluetoothGatt.requestMtu] is silently dropped by the peer (some
     * Begode firmware just never replies), force discovery anyway so the
     * connect path doesn't stall until the supervision timeout fires.
     */
    private fun armMtuFallback(g: BluetoothGatt) {
        cancelMtuFallback()
        val r = Runnable {
            if (closed) return@Runnable
            Log.w(TAG, "onMtuChanged not received within ${MTU_FALLBACK_MILLIS}ms — proceeding to discoverServices()")
            startDiscovery(g, refreshFirst = false)
        }
        mtuFallback = r
        mainHandler.postDelayed(r, MTU_FALLBACK_MILLIS)
    }

    private fun cancelMtuFallback() {
        val r = mtuFallback ?: return
        mainHandler.removeCallbacks(r)
        mtuFallback = null
    }

    private data class ResolvedChars(
        val notify: BluetoothGattCharacteristic,
        val write: BluetoothGattCharacteristic,
    )

    /**
     * Bind the notify / write characteristics, preferring [topology]
     * but falling back to whichever topology the wheel actually
     * exposes.
     *
     * [topology] is derived from the *advertisement*, which for several
     * families is a guess: a board that advertises only `FFE0` looks
     * like the single-characteristic profile, yet Inmotion legacy
     * hardware advertising that way really speaks the split
     * `FFE0/FFE4` + `FFE5/FFE9` profile. Hard-failing on the guess
     * turned a recoverable mislabel into a dead connection, even though
     * service discovery had by this point returned the real answer.
     *
     * The fallback fixes the *link* only. The codec was chosen from the
     * same guess, so a fallback hit means telemetry will not decode
     * until the family is corrected — hence the warning, which names
     * the topology actually found.
     */
    private fun resolveCharacteristics(
        g: BluetoothGatt,
        topology: GattTopology,
    ): ResolvedChars? {
        resolveExactly(g, topology)?.let { return it }
        for (candidate in GattTopology.entries) {
            if (candidate == topology) continue
            val resolved = resolveExactly(g, candidate) ?: continue
            Log.w(
                TAG,
                "expected topology=$topology is absent; the wheel exposes " +
                    "$candidate — binding that instead. The family hint was " +
                    "wrong, so the codec is probably wrong too and telemetry " +
                    "may not decode.",
            )
            return resolved
        }
        return null
    }

    private fun resolveExactly(
        g: BluetoothGatt,
        topology: GattTopology,
    ): ResolvedChars? = when (topology) {
        GattTopology.SINGLE_CHAR -> {
            val svc = g.getService(GattUuids.SERVICE_FFE0) ?: return null
            val c = svc.getCharacteristic(GattUuids.CHAR_FFE1) ?: return null
            ResolvedChars(notify = c, write = c)
        }
        GattTopology.SPLIT_CHAR -> {
            val readSvc = g.getService(GattUuids.SERVICE_FFE0) ?: return null
            val writeSvc = g.getService(GattUuids.SERVICE_FFE5) ?: return null
            val notify = readSvc.getCharacteristic(GattUuids.CHAR_FFE4) ?: return null
            val write = writeSvc.getCharacteristic(GattUuids.CHAR_FFE9) ?: return null
            ResolvedChars(notify = notify, write = write)
        }
        GattTopology.NORDIC_UART -> {
            val svc = g.getService(GattUuids.SERVICE_NUS) ?: return null
            // Nordic UART standard: TX (0x0003) is what the peripheral
            // notifies on; RX (0x0002) is what the central writes to.
            val tx = svc.getCharacteristic(GattUuids.CHAR_NUS_TX) ?: return null
            val rx = svc.getCharacteristic(GattUuids.CHAR_NUS_RX) ?: return null
            ResolvedChars(notify = tx, write = rx)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCccDescriptor(
        g: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeDescriptor(descriptor, value)
            if (rc != BluetoothGatt.GATT_SUCCESS) {
                failConnect(IOException("writeDescriptor returned $rc"))
            }
        } else {
            descriptor.value = value
            if (!g.writeDescriptor(descriptor)) {
                failConnect(IOException("writeDescriptor returned false"))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun submitWrite(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean {
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeCharacteristic(ch, bytes, writeType)
            rc == BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = writeType
            ch.value = bytes
            g.writeCharacteristic(ch)
        }
    }

    private fun refreshGattCache(g: BluetoothGatt): Boolean = try {
        val m = g.javaClass.getMethod("refresh")
        (m.invoke(g) as? Boolean) == true
    } catch (t: Throwable) {
        Log.w(TAG, "refreshGattCache failed: ${t.message}")
        false
    }

    private companion object {
        const val TAG = "RideFlux/BLE"

        /**
         * Delay between STATE_CONNECTED and the first GATT op (an MTU
         * request). Lets the link-layer connection update settle before
         * we issue the first ATT request.
         */
        const val POST_CONNECT_SETTLE_MILLIS: Long = 700L

        /** Target ATT MTU. 247 = max payload before LL fragmentation on most stacks. */
        const val REQUESTED_MTU: Int = 247

        /** If onMtuChanged doesn't arrive within this window, proceed to discovery anyway. */
        const val MTU_FALLBACK_MILLIS: Long = 1500L

        /** Android-internal GATT_ERROR (0x85). */
        const val GATT_ERROR_133: Int = 133

        /**
         * Maximum number of additional connectGatt() attempts after
         * a status=133 disconnect during the initial connect window.
         * Two retries (so up to three attempts total) is sufficient
         * for every wheel BT stack we've reproduced this on without
         * dragging out the failure path when the wheel really is off.
         */
        const val MAX_CONNECT_RETRIES: Int = 2

        /** Settle delay between a status=133 disconnect and the next connectGatt(). */
        const val RECONNECT_DELAY_MILLIS: Long = 600L

        /**
         * Watchdog timeout for onServicesDiscovered. If the callback hasn't
         * fired within this many milliseconds we refresh the GATT cache
         * and retry discoverServices() once. Begode boards typically
         * respond well under 1 s, so 3500 ms is generous without dragging
         * out the failure path.
         */
        const val DISCOVER_WATCHDOG_MILLIS: Long = 3500L

        /** Max additional discoverServices() retries (refresh+discover) per connect. */
        const val MAX_DISCOVER_RETRIES: Int = 1

        /**
         * Watchdog timeout for onCharacteristicWrite. If the stack never
         * delivers the callback (silent peer / flaky link), the write
         * fails with an IOException instead of suspending forever while
         * holding opMutex — which would also block disconnect().
         */
        const val WRITE_TIMEOUT_MILLIS: Long = 5000L
    }
}
