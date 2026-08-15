/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.source

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.rideflux.data.bridge.BridgeProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class BridgePeerCandidate(val address: String, val name: String?, val rssi: Int)

@SuppressLint("MissingPermission")
class BridgePeerScanner(context: Context) {
    private val context = context.applicationContext

    fun candidates(): Flow<List<BridgePeerCandidate>> = callbackFlow {
        val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth scanner unavailable"))
            return@callbackFlow
        }
        val found = mutableMapOf<String, BridgePeerCandidate>()
        // Vendor stacks (early RV101 firmware) can mishandle hardware
        // service-UUID filters and return nothing at all; after a short
        // silence restart the scan unfiltered and match the advertised
        // UUID in code, mirroring BridgeClient's fallback.
        val filterExpired = AtomicBoolean(false)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (
                    filterExpired.get() &&
                    result.scanRecord?.serviceUuids?.any { it.uuid == BridgeProtocol.SERVICE_UUID } != true
                ) {
                    return
                }
                val candidate = BridgePeerCandidate(
                    address = result.device.address,
                    name = result.scanRecord?.deviceName,
                    rssi = result.rssi,
                )
                found[candidate.address] = candidate
                trySend(found.values.sortedByDescending(BridgePeerCandidate::rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Bridge scan failed: $errorCode"))
            }
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BridgeProtocol.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, callback)

        launch {
            delay(FILTER_FALLBACK_MILLIS)
            if (found.isEmpty() && !filterExpired.getAndSet(true)) {
                Log.w(
                    TAG,
                    "no candidates with service-UUID filter after ${FILTER_FALLBACK_MILLIS}ms — " +
                        "restarting unfiltered",
                )
                try {
                    scanner.stopScan(callback)
                } catch (_: Throwable) { /* already stopped */ }
                try {
                    scanner.startScan(emptyList(), settings, callback)
                } catch (t: Throwable) {
                    close(t)
                }
            }
        }

        awaitClose { scanner.stopScan(callback) }
    }

    private companion object {
        const val TAG = "BridgePeerScanner"
        const val FILTER_FALLBACK_MILLIS = 4_000L
    }
}
