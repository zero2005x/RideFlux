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
import com.rideflux.data.bridge.BridgeProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
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
        awaitClose { scanner.stopScan(callback) }
    }
}
