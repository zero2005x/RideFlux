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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.wheel.WheelFamily
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Production [WheelRepository] backed by the Android platform BLE APIs.
 *
 * * [scan] — runs an unfiltered BLE scan and emits a deduplicated list
 *   of the advertisers that resolve to a known [WheelFamily], by
 *   advertised name first and service UUIDs second (see
 *   [WheelCodecFactoryImpl.inferFromAdvertisement]). Devices that
 *   resolve to no family are dropped in-process. Controller-side
 *   ScanFilters are deliberately not used: they can only match the
 *   advertising PDU, which several wheel families (notably Inmotion
 *   legacy) populate with a name and no service UUID at all.
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
    private val codecFactory: BleWheelCodecFactory = WheelCodecFactoryImpl(),
) : WheelRepository {

    // Deferred until first use (scan/connect run off the main thread) so
    // the binder call to BluetoothManagerService never stalls composition.
    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // ---- Active connection book-keeping --------------------------------

    private data class Entry(
        val connection: WheelConnectionImpl,
        val scopeJob: Job,
        var refCount: Int,
        /**
         * Non-null once teardown has begun, completing when the GATT
         * link is actually gone.
         *
         * The entry stays in [entries] for the whole of teardown so a
         * concurrent connect() for the same MAC cannot slip past and
         * call connectGatt() while the previous session is still
         * disconnecting — Android allows only one active GATT client
         * connection per app per remote device, and the stale one can
         * tear down the new link.
         */
        var closing: CompletableDeferred<Unit>? = null,
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
                Log.e(TAG, "no BluetoothLeScanner — adapter null or Bluetooth off")
                close(IOException("Bluetooth LE scanner unavailable (adapter off?)"))
                return@callbackFlow
            }

        val seen = linkedMapOf<String, DiscoveredWheel>()
        // Advertisement facts accumulated per address across packets.
        // A BLE device splits its payload between the advertisement and
        // the scan response, and Android surfaces whichever arrived —
        // so the name and the service UUIDs routinely reach us in
        // *different* callbacks, each with the other field null. Keeping
        // only the latest packet's view would flip a wheel in and out of
        // the list as the two packet types alternate.
        val names = mutableMapOf<String, String>()
        val serviceUuids = mutableMapOf<String, MutableSet<String>>()
        /** Addresses already reported as unrecognised, so the log stays readable. */
        val seenUnmatched = mutableSetOf<String>()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handle(result)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handle)
            }
            override fun onScanFailed(errorCode: Int) {
                // errorCode 1 = ALREADY_STARTED, 2 = APPLICATION_REGISTRATION_FAILED,
                // 3 = INTERNAL_ERROR, 4 = FEATURE_UNSUPPORTED, 5 = OUT_OF_HARDWARE_RESOURCES,
                // 6 = SCANNING_TOO_FREQUENTLY.
                Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
                close(IOException("BLE scan failed: errorCode=$errorCode"))
            }

            private fun handle(result: ScanResult) {
                val address = result.device?.address ?: return
                result.scanRecord?.serviceUuids
                    ?.map { it.uuid.toString() }
                    ?.let { serviceUuids.getOrPut(address) { mutableSetOf() }.addAll(it) }
                val name = (result.scanRecord?.deviceName ?: result.device.name)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.also { names[address] = it }
                    ?: names[address]

                val uuidStrings: Set<String> = serviceUuids[address] ?: emptySet()
                val family = codecFactory.inferFromAdvertisement(name, uuidStrings)
                if (family == null) {
                    // Not a wheel we can decode. Logged (once the name is
                    // known) rather than silently dropped: when a wheel
                    // fails to appear in the list this line is the whole
                    // diagnosis — it prints exactly what the board put on
                    // the air, so a missing model can be added to
                    // WheelNameClassifier from a logcat capture.
                    if (name != null && seenUnmatched.add(address)) {
                        Log.d(TAG, "ignoring $address name=$name services=$uuidStrings")
                    }
                    return
                }
                val wheel = DiscoveredWheel(
                    address = address,
                    displayName = name,
                    rssi = result.rssi,
                    family = family,
                )
                // Dedupe on stable identity only. DiscoveredWheel is a
                // data class that includes rssi, and in
                // SCAN_MODE_LOW_LATENCY the RSSI changes on nearly every
                // advertisement — comparing whole values meant the guard
                // almost never fired and the full list was rebuilt and
                // re-emitted per scan result, flooding collectors and
                // defeating the trailing distinctUntilChanged().
                val prior = seen[address]
                val identityUnchanged = prior != null &&
                    prior.displayName == wheel.displayName &&
                    prior.family == wheel.family
                // Keep the latest RSSI regardless, so a later emission
                // (triggered by some other device) carries fresh signal.
                seen[address] = wheel
                if (identityUnchanged) return
                if (prior == null) {
                    Log.i(
                        TAG,
                        "discovered $address name=${wheel.displayName} " +
                            "family=$family rssi=${result.rssi} services=$uuidStrings",
                    )
                }
                trySend(seen.values.toList())
            }
        }

        try {
            // Deliberately unfiltered (null filters), with the wheel/
            // not-a-wheel decision made in handle() above.
            //
            // The previous implementation passed one
            // ScanFilter.setServiceUuid() per entry of
            // GattUuids.ALL_PRIMARY_SERVICES. That filter is evaluated
            // by the Bluetooth controller against the advertising PDU,
            // so it only ever matched boards that spend some of their 31
            // advertising bytes on a service UUID — and Inmotion legacy
            // boards (V5 / V8 / V10) spend theirs on the local name
            // instead. Their FFE0/FFE5 services exist only in the GATT
            // table, which is not readable until after connecting, so
            // the filter dropped every such wheel before onScanResult
            // and they could never be selected.
            //
            // Cost of dropping the filter: the callback now sees every
            // advertiser in range, so handle() must stay cheap (it is:
            // map lookups plus a regex). Note that Android blocks
            // unfiltered scans while the screen is off — acceptable
            // because scan() is only collected by the foreground
            // scanner screen; the background BridgeService reconnects by
            // MAC through connect() and never scans.
            scanner.startScan(/* filters = */ null, settings, cb)
            // Logged at INFO on purpose: "the wheel is not in the list"
            // is ambiguous between "the scan never ran" and "the scan
            // ran and the wheel never advertised", and only the app can
            // tell those apart. This line plus the stop line below
            // bracket the window in which the absence is meaningful.
            Log.i(TAG, "BLE scan started (unfiltered)")
        } catch (t: Throwable) {
            Log.e(TAG, "startScan threw: ${t.message}", t)
            close(t); return@callbackFlow
        }
        awaitClose {
            try { scanner.stopScan(cb) } catch (_: Throwable) { /* best-effort */ }
            Log.i(
                TAG,
                "BLE scan stopped; matched=${seen.size} ignored=${seenUnmatched.size}",
            )
        }
    }.distinctUntilChanged()

    // ---- Connect -------------------------------------------------------

    override suspend fun connect(
        address: String,
        expectedFamily: WheelFamily?,
    ): WheelConnection {
        while (true) {
            // Work that must happen outside the global lock, decided
            // while holding it.
            var pendingTeardown: Entry? = null
            var awaitTeardown: CompletableDeferred<Unit>? = null

            val handle: WheelConnection? = connectMutex.withLock {
                val existing = entries[address]
                when {
                    existing == null -> newEntryLocked(address, expectedFamily)

                    // Teardown for this MAC is already in flight: wait
                    // for the GATT link to be gone, then re-evaluate.
                    existing.closing != null -> {
                        awaitTeardown = existing.closing
                        null
                    }

                    // Reusing a terminally-Failed connection would hand
                    // the caller a dead handle that never recovers, and
                    // the dead entry would sit in activeConnections()
                    // until someone happened to close it. Evict and
                    // rebuild instead.
                    existing.connection.state.value is ConnectionState.Failed -> {
                        val done = CompletableDeferred<Unit>()
                        existing.closing = done
                        awaitTeardown = done
                        pendingTeardown = existing
                        null
                    }

                    else -> {
                        existing.refCount++
                        SharedWheelConnection(address, existing.connection)
                    }
                }
            }

            if (handle != null) return handle
            pendingTeardown?.let { tearDown(address, it) }
            awaitTeardown?.await()
        }
    }

    /** Build and register a fresh entry. Caller must hold [connectMutex]. */
    private fun newEntryLocked(
        address: String,
        expectedFamily: WheelFamily?,
    ): WheelConnection {
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

        entries[address] = Entry(conn, entryJob, refCount = 1)
        publishActive()

        // Kick off the connection; errors surface through
        // WheelConnection.state as ConnectionState.Failed.
        entryScope.launch { conn.start() }

        return SharedWheelConnection(address, conn)
    }

    /**
     * Close the connection, cancel its scope and only then drop the
     * entry, completing [Entry.closing] so waiters can proceed.
     *
     * [entry] must already be marked closing.
     */
    private suspend fun tearDown(address: String, entry: Entry) {
        // NonCancellable: a cancelled caller must not abandon a
        // half-torn-down entry — the closing latch would never complete
        // and every future connect() for this MAC would hang.
        withContext(NonCancellable) {
            try {
                entry.connection.close()
            } catch (_: Throwable) {
                // best-effort; the scope cancel below still runs
            }
            entry.scopeJob.cancel()
            connectMutex.withLock {
                // Only remove our own entry: a waiter that already
                // rebuilt one for this address must not be evicted.
                if (entries[address] === entry) {
                    entries.remove(address)
                    publishActive()
                }
            }
            entry.closing?.complete(Unit)
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

        private val released = java.util.concurrent.atomic.AtomicBoolean(false)

        override suspend fun close() {
            // NonCancellable around the whole critical section: close()
            // is typically invoked from scope-cleanup coroutines that
            // are themselves being cancelled. Without this, withLock
            // throws CancellationException before the refCount
            // decrement, nobody retries, and the entry (transport, live
            // GATT link, entryScope, plus a stale row in
            // activeConnections()) leaks indefinitely.
            withContext(NonCancellable) {
                var entryToTearDown: Entry? = null
                connectMutex.withLock {
                    if (!released.compareAndSet(false, true)) {
                        return@withLock
                    }
                    val entry = entries[address] ?: return@withLock
                    entry.refCount -= 1
                    if (entry.refCount <= 0 && entry.closing == null) {
                        // Mark closing but LEAVE the entry in the map:
                        // it is only removed once the GATT link is
                        // really gone, so a concurrent connect() for
                        // this MAC waits instead of opening a second
                        // session against a still-disconnecting link.
                        entry.closing = CompletableDeferred()
                        entryToTearDown = entry
                    }
                }
                // Teardown runs outside the global lock so a slow GATT
                // disconnect cannot stall connect() for unrelated
                // addresses.
                entryToTearDown?.let { tearDown(address, it) }
            }
        }
    }

    private companion object {
        const val TAG = "RideFlux/BLE"
    }
}
