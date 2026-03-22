package com.wheellog.next.data.ble

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.characteristicOf
import com.juul.kable.peripheral
import com.wheellog.next.domain.model.ConnectionState
import com.wheellog.next.domain.model.TelemetryState
import com.wheellog.next.domain.model.WheelType
import com.wheellog.next.domain.protocol.ProtocolDecoder
import com.wheellog.next.domain.repository.DiscoveredDevice
import com.wheellog.next.domain.repository.EucRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kable-based implementation of [EucRepository].
 * Handles BLE scanning, connection lifecycle, characteristic observation,
 * and automatic reconnection on unexpected disconnects.
 */
@Singleton
class KableEucRepository @Inject constructor(
    private val scope: CoroutineScope,
    private val protocolDecoder: ProtocolDecoder,
) : EucRepository {

    companion object {
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val INITIAL_CONNECT_MAX_ATTEMPTS = 3
        private val INITIAL_CONNECT_BACKOFF_MS = longArrayOf(500L, 1_000L, 2_000L)
    }

    private val cachedAdvertisements = ConcurrentHashMap<String, com.juul.kable.Advertisement>()
    private var peripheral: Peripheral? = null
    private var observeJob: Job? = null
    private var reconnectJob: Job? = null
    private var detectedWheelType: WheelType = WheelType.UNKNOWN
    private var lastConnectedAddress: String? = null
    private var intentionalDisconnect = false

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    private val _telemetry = MutableStateFlow(TelemetryState())

    override fun observeConnectionState(): Flow<ConnectionState> = _connectionState.asStateFlow()

    override fun observeTelemetry(): Flow<TelemetryState> = _telemetry

    override fun scanDevices(): Flow<List<DiscoveredDevice>> {
        val accumulated = mutableMapOf<String, DiscoveredDevice>()
        return Scanner().advertisements.map { advertisement ->
            val address = advertisement.identifier.toString()
            cachedAdvertisements[address] = advertisement
            accumulated[address] = DiscoveredDevice(
                name = advertisement.name,
                address = address,
                rssi = advertisement.rssi,
            )
            accumulated.values.toList()
        }
    }

    override suspend fun connect(address: String) {
        disconnect()
        intentionalDisconnect = false
        lastConnectedAddress = address
        _connectionState.value = ConnectionState.CONNECTING

        for (attempt in 0 until INITIAL_CONNECT_MAX_ATTEMPTS) {
            try {
                connectInternal(address)
                return // Connected successfully
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Clean up the failed peripheral to release the GATT client
                // (Android BLE requires gatt.close() before retrying)
                peripheral?.disconnect()
                peripheral = null
                if (attempt < INITIAL_CONNECT_MAX_ATTEMPTS - 1) {
                    delay(INITIAL_CONNECT_BACKOFF_MS[attempt])
                }
            }
        }
        // All attempts exhausted
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun disconnect() {
        intentionalDisconnect = true
        _connectionState.value = ConnectionState.DISCONNECTING
        reconnectJob?.cancel()
        reconnectJob = null
        observeJob?.cancel()
        observeJob = null
        peripheral?.disconnect()
        peripheral = null
        detectedWheelType = WheelType.UNKNOWN
        lastConnectedAddress = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private suspend fun connectInternal(address: String) {
        val advertisement = cachedAdvertisements[address]
            ?: throw IllegalStateException("Device $address not found in scan cache")

        detectedWheelType = protocolDecoder.detectWheelType(advertisement.name)
        val profile = protocolDecoder.bleProfileFor(detectedWheelType)
            ?: throw IllegalStateException("No BLE profile for wheel type $detectedWheelType")

        val p = scope.peripheral(advertisement)
        peripheral = p
        p.connect()
        _connectionState.value = ConnectionState.CONNECTED

        val characteristic = characteristicOf(
            service = profile.serviceUuid,
            characteristic = profile.notifyCharacteristicUuid,
        )

        observeJob = scope.launch {
            try {
                p.observe(characteristic).collect { bytes ->
                    protocolDecoder.decode(detectedWheelType, bytes)?.let { state ->
                        _telemetry.value = state
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Connection lost — schedule reconnect
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect(address)
            }
        }
    }

    private fun scheduleReconnect(address: String) {
        if (intentionalDisconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
                delay(RECONNECT_DELAY_MS)
                if (intentionalDisconnect) return@launch
                // Clean up stale peripheral before retrying
                peripheral?.disconnect()
                peripheral = null
                _connectionState.value = ConnectionState.CONNECTING
                try {
                    connectInternal(address)
                    return@launch // Reconnected successfully
                } catch (_: CancellationException) {
                    return@launch
                } catch (_: Exception) {
                    // Retry on next iteration
                }
            }
            // All reconnect attempts exhausted
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }
}
