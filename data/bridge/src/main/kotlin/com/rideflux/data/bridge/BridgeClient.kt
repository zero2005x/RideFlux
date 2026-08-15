/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * AR-glasses-side BLE central. Scans for the bridge advertised by
 * [BridgeServer], connects, subscribes to the telemetry
 * characteristic and exposes each decoded notification as a
 * [BridgeFrame] on the returned [Flow].
 *
 * Cancelling collection of the returned flow tears the BLE
 * connection down. Re-subscribing starts a fresh scan / connect
 * cycle so that mid-flight reconnects are honest about state
 * transitions.
 *
 * Permissions required at the Manifest level: BLUETOOTH_SCAN,
 * BLUETOOTH_CONNECT.
 *
 * ### Peer verification
 * The service UUID alone is not an identity: anyone can advertise it
 * and inject fabricated telemetry into the HUD. [peerFilter] decides
 * which advertiser is acceptable and is enforced *before*
 * `connectGatt`. Production deployments should use
 * [BridgePeerFilter.Allowlist] (the paired phone's MAC) or
 * [BridgePeerFilter.Bonded]; [BridgePeerFilter.AcceptAny] is the
 * historical behaviour and is logged as insecure on every use.
 */
@SuppressLint("MissingPermission")
class BridgeClient(
    context: Context,
    private val peerFilter: BridgePeerFilter = BridgePeerFilter.AcceptAny,
) {

    // Normalise to the application context: this class retains it for
    // the whole lifetime of the returned flow and hands it to
    // getSystemService()/connectGatt(). An Activity- or
    // ViewModel-scoped context would leak GATT/scan resources past its
    // own scope.
    private val context: Context = context.applicationContext

    /**
     * Open a hot flow of frames. Emits nothing during scan/connect
     * and starts emitting once the CCCD write completes. The flow
     * completes (closes) when the link drops; callers usually wrap
     * it in `retryWhen { ... delay; true }`.
     */
    fun frames(): Flow<BridgeFrame> = callbackFlow {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("BluetoothLeScanner unavailable"))
            return@callbackFlow
        }

        // Written from the BLE callback thread (onScanResult), read from
        // the flow dispatcher in awaitClose. A plain local gives no
        // happens-before edge, so the cleanup could observe null and
        // skip disconnect()/close(), leaking a live GATT link. An
        // AtomicReference publishes it safely across both threads.
        val gattRef = AtomicReference<BluetoothGatt?>(null)
        // Guard against duplicate onScanResult deliveries opening a
        // second BluetoothGatt: each delivery would otherwise overwrite
        // the reference without closing the previous one, leaking the
        // link. Atomic so two concurrent deliveries cannot both win.
        val connectStarted = AtomicBoolean(false)
        // Set once the CCCD write is confirmed; until then the watchdog
        // below is armed.
        val subscribed = AtomicBoolean(false)
        // Set once the MTU exchange has been resolved one way or the
        // other (onMtuChanged arrived, requestMtu was rejected, or the
        // fallback timer fired), so service discovery starts exactly
        // once per connection.
        val mtuResolved = AtomicBoolean(false)
        val mtuFallbackJob = AtomicReference<Job?>(null)

        // Single funnel for service discovery so every code path (MTU
        // callback, MTU fallback, requestMtu rejection) exercises the
        // same guard-and-close behaviour instead of a bare call that
        // could throw SecurityException onto the BLE callback thread.
        fun startDiscovery(g: BluetoothGatt) {
            try {
                g.discoverServices()
            } catch (t: Throwable) {
                close(IllegalStateException("discoverServices failed: ${t.message}"))
            }
        }

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                Log.i(TAG, "conn status=$status newState=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // requestMtu's Boolean result matters: if it returns
                    // false the peer never sends an MTU response,
                    // onMtuChanged never fires, and the flow would hang
                    // forever (never completing, so retryWhen cannot
                    // recover). Fall back to discovery directly.
                    val mtuOk = try {
                        g.requestMtu(BridgeProtocol.PREFERRED_MTU)
                    } catch (t: Throwable) {
                        Log.w(TAG, "requestMtu threw", t)
                        false
                    }
                    if (!mtuOk) {
                        Log.w(TAG, "requestMtu rejected — discovering without MTU exchange")
                        mtuResolved.set(true)
                        startDiscovery(g)
                    } else {
                        // Some vendor BLE stacks (including early RV101
                        // firmware) accept the request but never deliver
                        // onMtuChanged. v2 frames fit the default MTU,
                        // so arm a short fallback that forces discovery
                        // rather than hanging until the subscribe
                        // watchdog tears the whole attempt down.
                        mtuFallbackJob.set(launch {
                            delay(MTU_FALLBACK_MILLIS)
                            if (!mtuResolved.getAndSet(true)) {
                                Log.w(
                                    TAG,
                                    "onMtuChanged not received within ${MTU_FALLBACK_MILLIS}ms — " +
                                        "proceeding to discoverServices()",
                                )
                                startDiscovery(g)
                            }
                        })
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    close(IllegalStateException("disconnected status=$status"))
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                Log.i(TAG, "mtu=$mtu status=$status")
                if (!mtuResolved.getAndSet(true)) {
                    // Even a failed MTU exchange must not block the
                    // connect path: v2 frames are sized for the default
                    // 23-byte MTU, so discover regardless of status.
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "MTU exchange failed status=$status — discovering anyway")
                    }
                    startDiscovery(g)
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "late MTU failure status=$status after discovery already started")
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    close(IllegalStateException("discoverServices status=$status"))
                    return
                }
                val svc = g.getService(BridgeProtocol.SERVICE_UUID)
                val ch = svc?.getCharacteristic(BridgeProtocol.TELEMETRY_CHAR_UUID)
                if (ch == null) {
                    val discovered = g.services.joinToString { it.uuid.toString() }
                    Log.w(TAG, "bridge service/char missing; discovered=[$discovered]")
                    // Android caches a peer's GATT database by address.
                    // The phone's bridge service is dynamic, so a cache
                    // created before BridgeServer opened can remain empty
                    // even though the server now reports onServiceAdded=0.
                    // Refresh once on this definitive mismatch; the outer
                    // retry loop then opens a new GATT and discovers the
                    // current database.
                    refreshGattCache(g)
                    close(IllegalStateException("bridge service/char missing"))
                    return
                }
                // Guard the remaining GATT calls like connectGatt above:
                // if BLUETOOTH_CONNECT is revoked at runtime the
                // SecurityException would otherwise escape onto the BLE
                // callback thread and crash the app instead of
                // surfacing through close(...) for retryWhen.
                try {
                    if (!g.setCharacteristicNotification(ch, true)) {
                        close(IllegalStateException("setCharacteristicNotification failed"))
                        return
                    }
                    val cccd = ch.getDescriptor(BridgeProtocol.CCCD_UUID)
                    if (cccd == null) {
                        close(IllegalStateException("CCCD missing"))
                        return
                    }
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (!g.writeDescriptor(cccd)) {
                        close(IllegalStateException("writeDescriptor failed"))
                    }
                } catch (t: Throwable) {
                    close(IllegalStateException("CCCD subscribe failed: ${t.message}"))
                }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // The CCCD write result is the signal that the server
                    // will start sending notifications. If it failed the
                    // flow would stay open and silently silent — surface it
                    // so callers' retryWhen can recover.
                    close(IllegalStateException("CCCD write failed status=$status"))
                } else {
                    subscribed.set(true)
                }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid != BridgeProtocol.TELEMETRY_CHAR_UUID) return
                val bytes = characteristic.value
                if (bytes == null) {
                    Log.w(TAG, "bridge notification arrived with null value; dropping")
                    return
                }
                val frame = BridgeCodec.decode(bytes)
                if (frame == null) {
                    // Previously dropped silently, which made "connected
                    // but no data" impossible to diagnose. Log length and
                    // a hex prefix so a truncated notification (e.g. a
                    // stale v1 32-byte frame under a small MTU) is
                    // visible in a bug report.
                    Log.w(
                        TAG,
                        "discarding undecodable bridge notification: " +
                            "len=${bytes.size} hex=${bytes.toHexPrefix()}",
                    )
                    return
                }
                // Log (rather than silently dropping) when the channel
                // buffer is full so lossy behaviour stays visible.
                if (!trySend(frame).isSuccess) {
                    Log.w(TAG, "frame dropped: trySend buffer full")
                }
            }
        }

        // Some vendor stacks (early RV101 firmware) mishandle
        // service-UUID scan filters and simply never report results.
        // The wheel scanner already learned this lesson ("unfiltered
        // scan, classify in process") — mirror it here: start filtered,
        // and after a short silence restart without a filter, matching
        // the advertised UUID in code instead.
        val filterExpired = AtomicBoolean(false)

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (
                    filterExpired.get() &&
                    result.scanRecord?.serviceUuids?.any { it.uuid == BridgeProtocol.SERVICE_UUID } != true
                ) {
                    return
                }
                if (!peerFilter.accepts(device)) return
                if (!connectStarted.compareAndSet(false, true)) return
                Log.i(TAG, "scan hit: ${device.address} rssi=${result.rssi}")
                // Stop scanning immediately and connect.
                try {
                    scanner.stopScan(this)
                } catch (_: Throwable) { /* already stopped */ }
                // connectGatt can throw SecurityException if
                // BLUETOOTH_CONNECT was revoked at runtime, and can
                // return null on failure — both must surface through
                // close(...) instead of crashing the callback thread
                // or hanging the flow.
                val newGatt = try {
                    device.connectGatt(context, /* autoConnect = */ false, gattCallback)
                } catch (t: Throwable) {
                    close(IllegalStateException("connectGatt failed: ${t.message}"))
                    return
                }
                if (newGatt == null) {
                    close(IllegalStateException("connectGatt returned null"))
                    return
                }
                gattRef.set(newGatt)
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("scan failed: $errorCode"))
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BridgeProtocol.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            Log.i(TAG, "scan start service=${BridgeProtocol.SERVICE_UUID}")
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (t: Throwable) {
            close(t)
        }

        // Vendor-stack fallback: if the hardware UUID filter produced
        // nothing for a few seconds, restart the same scan unfiltered
        // and rely on the in-code serviceUuids check in onScanResult.
        // The overall watchdog still bounds the attempt so a dead scan
        // cannot hang the outer retry loop forever.
        launch {
            delay(SCAN_FILTER_FALLBACK_MILLIS)
            if (!connectStarted.get() && !filterExpired.getAndSet(true)) {
                Log.w(
                    TAG,
                    "no hits with service-UUID filter after ${SCAN_FILTER_FALLBACK_MILLIS}ms — " +
                        "restarting unfiltered",
                )
                try {
                    scanner.stopScan(scanCallback)
                } catch (_: Throwable) { /* already stopped */ }
                try {
                    scanner.startScan(emptyList(), settings, scanCallback)
                } catch (t: Throwable) {
                    close(t)
                }
            }
        }

        // Some vendor BLE stacks (including early RV101 firmware) can
        // leave a scan or half-open GATT silent forever without calling
        // onScanFailed/onConnectionStateChange. Close this collection so
        // the HUD's outer retry loop re-registers a fresh scan.
        val watchdog = launch {
            val scanDeadline = SystemClock.elapsedRealtime() + SCAN_TIMEOUT_MILLIS
            while (!connectStarted.get() && SystemClock.elapsedRealtime() < scanDeadline) {
                delay(WATCHDOG_POLL_MILLIS)
            }
            if (!connectStarted.get()) {
                Log.w(TAG, "scan timeout; restarting")
                close(IllegalStateException("bridge scan timeout"))
                return@launch
            }
            val subscribeDeadline = SystemClock.elapsedRealtime() + SUBSCRIBE_TIMEOUT_MILLIS
            while (!subscribed.get() && SystemClock.elapsedRealtime() < subscribeDeadline) {
                delay(WATCHDOG_POLL_MILLIS)
            }
            if (!subscribed.get()) {
                Log.w(TAG, "GATT subscribe timeout; restarting")
                close(IllegalStateException("bridge subscribe timeout"))
            }
        }

        awaitClose {
            watchdog.cancel()
            try { scanner.stopScan(scanCallback) } catch (_: Throwable) { }
            val activeGatt = gattRef.getAndSet(null)
            try { activeGatt?.disconnect() } catch (_: Throwable) { }
            try { activeGatt?.close() } catch (_: Throwable) { }
        }
    }

    private fun refreshGattCache(gatt: BluetoothGatt): Boolean = try {
        val refresh = gatt.javaClass.getMethod("refresh")
        val accepted = refresh.invoke(gatt) as? Boolean == true
        Log.i(TAG, "GATT cache refresh accepted=$accepted")
        accepted
    } catch (t: Throwable) {
        Log.w(TAG, "GATT cache refresh unavailable: ${t.message}")
        false
    }

    private companion object {
        const val TAG = "BridgeClient"
        const val SCAN_TIMEOUT_MILLIS = 12_000L
        const val SUBSCRIBE_TIMEOUT_MILLIS = 10_000L
        const val WATCHDOG_POLL_MILLIS = 250L
        const val SCAN_FILTER_FALLBACK_MILLIS = 4_000L
        const val MTU_FALLBACK_MILLIS = 1_500L
    }
}

private const val HEX_PREFIX_LENGTH = 16

/** First [HEX_PREFIX_LENGTH] bytes as lowercase hex, `…` when truncated. */
private fun ByteArray.toHexPrefix(): String {
    val shown = take(HEX_PREFIX_LENGTH)
    val hex = shown.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    return if (size > HEX_PREFIX_LENGTH) "$hex…" else hex
}
