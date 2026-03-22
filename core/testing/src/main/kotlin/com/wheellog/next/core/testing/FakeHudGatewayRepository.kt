package com.wheellog.next.core.testing

import com.wheellog.next.domain.model.HudPayload
import com.wheellog.next.domain.repository.HudGatewayRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake [HudGatewayRepository] for unit testing.
 */
class FakeHudGatewayRepository : HudGatewayRepository {

    private val _isAdvertising = MutableStateFlow(false)
    override val isAdvertising: Flow<Boolean> = _isAdvertising

    private val _connectedDeviceCount = MutableStateFlow(0)
    override val connectedDeviceCount: Flow<Int> = _connectedDeviceCount

    var lastPayload: HudPayload? = null
        private set
    var startAdvertisingCount = 0
        private set
    var stopAdvertisingCount = 0
        private set

    /** Simulate a device connecting/disconnecting for tests. */
    fun setConnectedDeviceCount(count: Int) {
        _connectedDeviceCount.value = count
    }

    override suspend fun startAdvertising() {
        startAdvertisingCount++
        _isAdvertising.value = true
    }

    override suspend fun stopAdvertising() {
        stopAdvertisingCount++
        _isAdvertising.value = false
    }

    override suspend fun pushPayload(payload: HudPayload) {
        lastPayload = payload
    }
}
