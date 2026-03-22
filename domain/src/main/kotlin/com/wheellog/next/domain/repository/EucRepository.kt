package com.wheellog.next.domain.repository

import com.wheellog.next.domain.model.ConnectionState
import com.wheellog.next.domain.model.TelemetryState
import kotlinx.coroutines.flow.Flow

/**
 * EUC connection repository interface — implemented by :data:ble.
 */
interface EucRepository {
    /** Observe BLE connection lifecycle state. */
    fun observeConnectionState(): Flow<ConnectionState>

    /** Observe real-time telemetry state */
    fun observeTelemetry(): Flow<TelemetryState>

    /** Scan for nearby EUC devices */
    fun scanDevices(): Flow<List<DiscoveredDevice>>

    /** Connect to the specified device */
    suspend fun connect(address: String)

    /** Disconnect from the current device */
    suspend fun disconnect()
}

data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
)
