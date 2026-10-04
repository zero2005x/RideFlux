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
import com.rideflux.domain.connection.ScooterConnection
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.repository.ScooterRepository
import com.rideflux.domain.safety.MotionInterlock
import com.rideflux.protocol.familyscooter.connection.ScooterConnectionImpl
import com.rideflux.protocol.familyscooter.ninebot.ScooterHandshakeStateMachine
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Android BLE discovery and reference-counted ownership of scooter sessions. */
@SuppressLint("MissingPermission")
class ScooterRepositoryImpl private constructor(
    private val context: Context?,
    private val rootScope: CoroutineScope,
    private val connectionFactory: ((String, String, CoroutineScope) -> ScooterConnection)?,
) : ScooterRepository {
    constructor(context: Context, rootScope: CoroutineScope) : this(context, rootScope, null)

    internal constructor(
        rootScope: CoroutineScope,
        connectionFactory: (String, String, CoroutineScope) -> ScooterConnection,
    ) : this(null, rootScope, connectionFactory)

    private val adapter: BluetoothAdapter? by lazy {
        (context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private data class Entry(
        val connection: ScooterConnection,
        val scopeJob: Job,
        var references: Int,
        var closing: CompletableDeferred<Unit>? = null,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val connectMutex = Mutex()
    private val names = ConcurrentHashMap<String, String>()
    private val active = MutableStateFlow<Map<String, ScooterConnection>>(emptyMap())

    override fun activeConnections(): Flow<Map<String, ScooterConnection>> = active.asStateFlow()
    override fun isDiscovered(address: String): Boolean = names.containsKey(address)

    override fun scan(): Flow<List<ScooterDevice>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            close(IOException("Bluetooth LE scanner unavailable"))
            return@callbackFlow
        }
        val found = linkedMapOf<String, ScooterDevice>()
        val seenNames = mutableMapOf<String, String>()
        val seenServices = mutableMapOf<String, MutableSet<String>>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
            override fun onScanFailed(errorCode: Int) {
                close(IOException("BLE scan failed: errorCode=$errorCode"))
            }

            private fun handle(result: ScanResult) {
                val address = result.device?.address ?: return
                result.scanRecord?.serviceUuids?.map { it.uuid.toString() }?.let {
                    seenServices.getOrPut(address) { mutableSetOf() }.addAll(it)
                }
                val advertised = (result.scanRecord?.deviceName ?: result.device.name)
                    ?.trim()?.takeIf(String::isNotEmpty)
                if (advertised != null) seenNames[address] = advertised
                val model = ScooterClassifier.classify(
                    seenNames[address], seenServices[address].orEmpty()) ?: return
                names[address] = model
                val candidate = ScooterDevice(address, model)
                if (found[address] == candidate) return
                found[address] = candidate
                trySend(found.values.toList())
            }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        } catch (failure: Exception) {
            close(failure)
            return@callbackFlow
        }
        awaitClose {
            try { scanner.stopScan(callback) } catch (_: Exception) { /* best effort */ }
        }
    }.distinctUntilChanged()

    override suspend fun connect(address: String): ScooterConnection {
        while (true) {
            var pendingTeardown: Entry? = null
            var awaitTeardown: CompletableDeferred<Unit>? = null
            val handle = connectMutex.withLock {
                val existing = entries[address]
                when {
                    existing == null -> newEntryLocked(address)
                    existing.closing != null -> {
                        awaitTeardown = existing.closing
                        null
                    }
                    existing.connection.state.value is ConnectionState.Failed -> {
                        val done = CompletableDeferred<Unit>()
                        existing.closing = done
                        awaitTeardown = done
                        pendingTeardown = existing
                        null
                    }
                    else -> {
                        existing.references++
                        SharedScooterConnection(address, existing.connection)
                    }
                }
            }
            if (handle != null) return handle
            pendingTeardown?.let { tearDown(address, it) }
            awaitTeardown?.await()
        }
    }

    private fun newEntryLocked(address: String): ScooterConnection {
        val entryJob = SupervisorJob(rootScope.coroutineContext[Job])
        val entryScope = rootScope + entryJob
        val model = names[address] ?: "Ninebot/Xiaomi Scooter"
        val connection = try {
            connectionFactory?.invoke(address, model, entryScope)
                ?: createPlatformConnection(address, model, entryScope)
        } catch (failure: Throwable) {
            entryJob.cancel()
            throw failure
        }
        entries[address] = Entry(connection, entryJob, references = 1)
        publishActive()
        entryScope.launch {
            try { connection.start() }
            catch (failure: Exception) { Log.e(TAG, "Scooter start failed for $address", failure) }
        }
        return SharedScooterConnection(address, connection)
    }

    private fun createPlatformConnection(
        address: String, model: String, entryScope: CoroutineScope,
    ): ScooterConnection {
        // The available connection state machine emits Ninebot 5A A5 frames. Xiaomi M365
        // uses a different framing/pairing profile and must not receive those commands.
        if (!ScooterClassifier.isNinebotRetailCandidate(model) ||
            model == "Ninebot/Xiaomi Scooter") {
            throw UnsupportedOperationException("No verified connection profile for $model")
        }
        val device = try { requireNotNull(adapter).getRemoteDevice(address) }
        catch (failure: Exception) { throw IOException("Invalid scooter address $address", failure) }
        val transport = AndroidBleTransport(
            context = requireNotNull(context), device = device, topology = GattTopology.NORDIC_UART)
        val interlock = MotionInterlock()
        return ScooterConnectionImpl(
            transport = transport,
            device = ScooterDevice(address, model),
            scope = entryScope,
            handshake = ScooterHandshakeStateMachine(interlock, System::currentTimeMillis),
            interlock = interlock,
            // An M365 capture does not establish the ES2 speed scale. Pairing controls and
            // lock writes therefore remain closed until a model-specific profile is verified.
            lockProfileVerified = false,
        )
    }

    private suspend fun tearDown(address: String, entry: Entry): Unit = withContext(NonCancellable) {
        try { entry.connection.close() } catch (_: Exception) { /* continue teardown */ }
        entry.scopeJob.cancel()
        connectMutex.withLock {
            if (entries[address] === entry) {
                entries.remove(address)
                publishActive()
            }
        }
        entry.closing?.complete(Unit)
        Unit
    }

    private fun publishActive() {
        active.value = entries.mapValues { it.value.connection }
    }

    private inner class SharedScooterConnection(
        private val address: String,
        private val delegate: ScooterConnection,
    ) : ScooterConnection by delegate {
        private val released = AtomicBoolean(false)

        override suspend fun close(): Unit = withContext(NonCancellable) {
            var toClose: Entry? = null
            connectMutex.withLock {
                if (!released.compareAndSet(false, true)) return@withLock
                val entry = entries[address] ?: return@withLock
                if (entry.connection !== delegate) return@withLock
                entry.references--
                if (entry.references == 0 && entry.closing == null) {
                    entry.closing = CompletableDeferred()
                    toClose = entry
                }
            }
            toClose?.let { tearDown(address, it) }
            Unit
        }
    }

    private companion object { const val TAG = "RideFlux/ScooterBLE" }
}
