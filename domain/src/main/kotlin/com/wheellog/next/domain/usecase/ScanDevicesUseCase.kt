package com.wheellog.next.domain.usecase

import com.wheellog.next.domain.repository.DiscoveredDevice
import com.wheellog.next.domain.repository.EucRepository
import kotlinx.coroutines.flow.Flow

/** Scan for nearby EUC BLE devices. */
class ScanDevicesUseCase(
    private val repository: EucRepository,
) {
    operator fun invoke(): Flow<List<DiscoveredDevice>> = repository.scanDevices()
}
