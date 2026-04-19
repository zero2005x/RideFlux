/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.wheel.WheelFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Production [WheelRepository] backed by the Android platform BLE APIs.
 *
 * * [scan] — filters advertisements on the three primary service
 *   UUIDs defined in `PROTOCOL_SPEC.md` §1 and emits a deduplicated
 *   list of [DiscoveredWheel]s. Each emission carries a best-guess
 *   [WheelFamily] derived from the advertisement's service UUID set
 *   (see [WheelCodecFactoryImpl.inferFromGattServiceUuids]).
 *
 * * [connect] — returns a shared [WheelConnection] per MAC address.
 *   A reference count tracks live users; the connection is closed
 *   and evicted from [activeConnections] once the last user calls
 *   [WheelConnection.close]. Callers get the same object back on
 *   every call for the same address, so multiple feature modules can
 *   safely observe the same telemetry stream.
 *
 * ### Permissions
 * The caller (typically `:app`) is responsible for granting
 * `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` and, on API 30 and earlier,
 * `ACCESS_FINE_LOCATION` before invoking either [scan] or [connect].
 */
@SuppressLint("MissingPermission")
class WheelRepositoryImpl(
    private val context: Context,
    private val rootScope: CoroutineScope,
    private val codecFactory: WheelCodecFactoryImpl = WheelCodecFactoryImpl(),
) : WheelRepository {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ---- Active connection book-keeping --------------------------------

    private data class Entry(
        val connection: WheelConnectionImpl,
        val scope: CoroutineScope,
        val scopeJob: Job,
        var refCount: Int,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val connectMutex = Mutex()

    private val _active = MutableStateFlow<Map<String, WheelConnection>>(emptyMap())

    override fun activeConnections(): Flow<Map<String, WheelConnection>> =
        _active.asStateFlow()

    // ---- Scan ----------------------------------------------------------

    override fun scan(): Flow<List<DiscoveredWheel>> = callbackFlow<List<DiscoveredWheel>> {
        val scanner = adapter?.bluetoothLeScanner
            ?: run {
                close(IOException("Bluetooth LE scanner unavailable (adapter off?)"))
                return@callbackFlow
            }

        val seen = linkedMapOf<String, DiscoveredWheel>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = GattUuids.ALL_PRIMARY_SERVICES.map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
        }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handle(result)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handle)
            }
            override fun onScanFailed(errorCode: Int) {
                close(IOException("BLE scan failed: errorCode=$errorCode"))
            }

            private fun handle(result: ScanResult) {
                val address = result.device?.address ?: return
                val uuidStrings = result.scanRecord?.serviceUuids
                    ?.map { it.uuid.toString() }
                    ?.toSet()
                    ?: emptySet()
                val family = codecFactory.inferFromGattServiceUuids(uuidStrings)
                val wheel = DiscoveredWheel(
                    address = address,
                    displayName = result.scanRecord?.deviceName ?: result.device.name,
                    rssi = result.rssi,
                    family = family,
                )
                val prior = seen[address]
                if (prior == wheel) return
                seen[address] = wheel
                trySend(seen.values.toList())
            }
        }

        try {
            scanner.startScan(filters, settings, cb)
        } catch (t: Throwable) {
            close(t); return@callbackFlow
        }
        awaitClose {
            try { scanner.stopScan(cb) } catch (_: Throwable) { /* best-effort */ }
        }
    }.distinctUntilChanged()

    // ---- Connect -------------------------------------------------------

    override suspend fun connect(
        address: String,
        expectedFamily: WheelFamily?,
    ): WheelConnection {
        connectMutex.withLock {
            entries[address]?.let { existing ->
                existing.refCount++
                return SharedWheelConnection(address, existing.connection)
            }

            val adapter = adapter ?: throw IOException("Bluetooth adapter unavailable")
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (t: Throwable) {
                throw IOException("invalid MAC $address", t)
            }

            val family = expectedFamily
                ?: throw IOException(
                    "family unknown for $address; pass expectedFamily (hint-only resolution " +
                        "from advertisement is not yet wired through connect())",
                )

            val codec = codecFactory.forFamilyWithAddress(family, address)
            val topology = codecFactory.topologyFor(family)

            val entryJob = SupervisorJob(parent = rootScope.coroutineContext[Job])
            val entryScope = rootScope + entryJob
            val transport = AndroidBleTransport(
                context = context,
                device = device,
                topology = topology,
                scope = entryScope,
            )
            val conn = WheelConnectionImpl(
                transport = transport,
                codec = codec,
                scope = entryScope,
            )

            entries[address] = Entry(conn, entryScope, entryJob, refCount = 1)
            publishActive()

            // Kick off the connection; errors surface through
            // WheelConnection.state as ConnectionState.Failed.
            entryScope.launch { conn.start() }

            return SharedWheelConnection(address, conn)
        }
    }

    private fun publishActive() {
        _active.value = entries.mapValues { it.value.connection as WheelConnection }
    }

    /**
     * Wrapper returned by [connect] that decrements the ref-count
     * when a caller closes its handle. The real teardown of the
     * transport + codec scope only runs when the last holder lets go.
     */
    private inner class SharedWheelConnection(
        private val address: String,
        private val delegate: WheelConnectionImpl,
    ) : WheelConnection by delegate {

        @Volatile private var released = false

        override suspend fun close() {
            if (released) return
            released = true
            connectMutex.withLock {
                val entry = entries[address] ?: return@withLock
                entry.refCount -= 1
                if (entry.refCount <= 0) {
                    entries.remove(address)
                    publishActive()
                    try { entry.connection.close() } catch (_: Throwable) { /* best-effort */ }
                    entry.scopeJob.cancel()
                }
            }
        }
    }
}
