package com.wheellog.next.feature.devicescan

import com.wheellog.next.domain.repository.DiscoveredDevice

/** User intents for the device-scan screen. */
sealed interface DeviceScanIntent {
    data object StartScan : DeviceScanIntent
    data object StopScan : DeviceScanIntent
    data class SelectDevice(val address: String) : DeviceScanIntent
}

/** UI state for the device-scan screen. */
data class DeviceScanState(
    val isScanning: Boolean = false,
    val devices: List<DiscoveredDevice> = emptyList(),
    val isConnecting: Boolean = false,
)

/** One-shot side effects for the device-scan screen. */
sealed interface DeviceScanEffect {
    data object NavigateToDashboard : DeviceScanEffect
    data class ShowError(val message: String) : DeviceScanEffect
}
