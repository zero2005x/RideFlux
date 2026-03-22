package com.wheellog.next.feature.devicescan

import androidx.lifecycle.viewModelScope
import com.wheellog.next.core.ui.mvi.ComposeViewModel
import com.wheellog.next.domain.repository.EucRepository
import com.wheellog.next.domain.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceScanViewModel @Inject constructor(
    private val eucRepository: EucRepository,
    private val preferencesRepository: PreferencesRepository,
) : ComposeViewModel<DeviceScanIntent, DeviceScanState, DeviceScanEffect>(DeviceScanState()) {

    private var scanJob: Job? = null

    override fun handleIntent(intent: DeviceScanIntent) {
        when (intent) {
            DeviceScanIntent.StartScan -> startScan()
            DeviceScanIntent.StopScan -> stopScan()
            is DeviceScanIntent.SelectDevice -> connectToDevice(intent.address)
        }
    }

    private fun startScan() {
        scanJob?.cancel()
        updateState { copy(isScanning = true, devices = emptyList()) }

        scanJob = viewModelScope.launch {
            eucRepository.scanDevices()
                .catch { e ->
                    updateState { copy(isScanning = false) }
                    emitEffect(DeviceScanEffect.ShowError(e.message ?: "Scan failed"))
                }
                .collect { devices ->
                    updateState { copy(devices = devices) }
                }
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        updateState { copy(isScanning = false) }
    }

    private fun connectToDevice(address: String) {
        stopScan()
        updateState { copy(isConnecting = true) }

        viewModelScope.launch {
            try {
                eucRepository.connect(address)
                preferencesRepository.saveLastDeviceAddress(address)
                updateState { copy(isConnecting = false) }
                emitEffect(DeviceScanEffect.NavigateToDashboard)
            } catch (e: Exception) {
                updateState { copy(isConnecting = false) }
                emitEffect(DeviceScanEffect.ShowError(e.message ?: "Connection failed"))
            }
        }
    }
}
