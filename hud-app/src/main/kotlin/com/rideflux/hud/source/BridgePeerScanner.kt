/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.source

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.rideflux.data.bridge.BleScanThrottle
import com.rideflux.data.bridge.BridgePairingToken
import com.rideflux.data.bridge.BridgeProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * A phone offering the bridge, as seen during pairing.
 *
 * @param address the advertising address at scan time. Useful for
 *   display and for the legacy MAC pairing path, but not an identity —
 *   Android rotates it.
 * @param tokenHex the phone's pairing token, or null for a phone
 *   running a build from before tokens existed.
 * @param shortCode four characters of [tokenHex] for the picker, so the
 *   rider can match what the phone's settings screen shows.
 */
data class BridgePeerCandidate(
    val address: String,
    val name: String?,
    val rssi: Int,
    val tokenHex: String? = null,
) {
    val shortCode: String? get() = BridgePairingToken.shortCodeOfHex(tokenHex)
}

@SuppressLint("MissingPermission")
class BridgePeerScanner(
    context: Context,
    private val scanThrottle: BleScanThrottle = BleScanThrottle.shared,
) {
    private val context = context.applicationContext

    /**
     * Nearby phones advertising the bridge, strongest signal first.
     *
     * Scans unfiltered and matches the service UUID in code: vendor
     * stacks on the target firmware mishandle hardware service-UUID
     * filters, and a single start keeps this within the platform's
     * five-scans-per-30-seconds budget even while [BridgeTelemetrySource]
     * is retrying in the background. See [BleScanThrottle].
     */
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
                val record = result.scanRecord ?: return
                if (record.serviceUuids?.any { it.uuid == BridgeProtocol.SERVICE_UUID } != true) {
                    return
                }
                val token = record
                    .getServiceData(ParcelUuid(BridgeProtocol.SERVICE_UUID))
                    ?.takeIf { it.size == BridgeProtocol.PAIRING_TOKEN_SIZE }
                val candidate = BridgePeerCandidate(
                    address = result.device.address,
                    name = record.deviceName,
                    rssi = result.rssi,
                    tokenHex = token?.let(BridgePairingToken::toHex),
                )
                // Key on the token where one exists: the same phone
                // re-advertising under a rotated address would otherwise
                // appear several times in the picker.
                found[candidate.tokenHex ?: candidate.address] = candidate
                trySend(found.values.sortedByDescending(BridgePeerCandidate::rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Bridge scan failed: $errorCode"))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        launch {
            val holdMillis = scanThrottle.reserve()
            if (holdMillis > 0L) {
                Log.w(TAG, "scan budget exhausted; holding pairing scan for ${holdMillis}ms")
                delay(holdMillis)
            }
            try {
                scanner.startScan(emptyList(), settings, callback)
            } catch (t: Throwable) {
                close(t)
            }
        }

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: Throwable) { /* already stopped */ }
        }
    }

    private companion object {
        const val TAG = "BridgePeerScanner"
    }
}
