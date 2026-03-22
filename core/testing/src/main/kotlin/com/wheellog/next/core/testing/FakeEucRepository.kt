package com.wheellog.next.core.testing

import com.wheellog.next.domain.model.ConnectionState
import com.wheellog.next.domain.model.TelemetryState
import com.wheellog.next.domain.repository.DiscoveredDevice
import com.wheellog.next.domain.repository.EucRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake [EucRepository] for unit testing ViewModels and use cases.
 */
class FakeEucRepository : EucRepository {

    val connectionStateFlow = MutableStateFlow(ConnectionState.IDLE)
    val telemetryFlow = MutableStateFlow(TelemetryState())
    val devicesFlow = MutableSharedFlow<List<DiscoveredDevice>>()

    var connectCalled = false
        private set
    var disconnectCalled = false
        private set
    var lastConnectedAddress: String? = null
        private set

    override fun observeConnectionState(): Flow<ConnectionState> = connectionStateFlow

    override fun observeTelemetry(): Flow<TelemetryState> = telemetryFlow

    override fun scanDevices(): Flow<List<DiscoveredDevice>> = devicesFlow

    override suspend fun connect(address: String) {
        connectCalled = true
        lastConnectedAddress = address
        connectionStateFlow.value = ConnectionState.CONNECTED
    }

    override suspend fun disconnect() {
        disconnectCalled = true
        connectionStateFlow.value = ConnectionState.DISCONNECTED
    }
}
