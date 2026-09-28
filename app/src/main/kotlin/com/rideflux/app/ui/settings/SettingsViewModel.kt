/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.settings

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rideflux.app.bridge.ApprovedGlasses
import com.rideflux.app.bridge.ApprovedGlassesStore
import com.rideflux.app.bridge.BridgePairingStore
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import android.net.Uri
import android.util.Log
import com.rideflux.app.backup.TripBackupManager
import com.rideflux.domain.ride.ImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface BackupUiEvent {
    data class ExportSuccess(val tripCount: Int) : BackupUiEvent
    data class ExportError(val message: String) : BackupUiEvent
    data class ImportSuccess(val result: ImportResult) : BackupUiEvent
    data class ImportError(val message: String) : BackupUiEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: SettingsRepository,
    private val backupManager: TripBackupManager,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings

    private val _backupEvent = MutableSharedFlow<BackupUiEvent>()
    val backupEvent: SharedFlow<BackupUiEvent> = _backupEvent.asSharedFlow()

    private val _pairingCode = MutableStateFlow<String?>(null)

    /**
     * This phone's bridge pairing code, shown so the rider can confirm
     * the glasses paired with the right phone. Resolved off the main
     * thread because reading it mints and commits the token on first
     * run.
     */
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

    val isLearningRingKey: StateFlow<Boolean> = RingKeyLearner.isListening

    init {
        viewModelScope.launch {
            _pairingCode.value = withContext(Dispatchers.IO) {
                BridgePairingStore.displayCode(appContext)
            }
        }
        viewModelScope.launch {
            RingKeyLearner.lastLearnedKey.collect { keyCode ->
                repository.setRingKeyCode(keyCode)
            }
        }
    }

    fun setSpeedLimit(value: Float) = update { repository.setSpeedLimitKmh(value) }
    fun setTemperatureLimit(value: Float) = update { repository.setTemperatureLimitC(value) }
    fun setLowBattery(value: Float) = update { repository.setLowBatteryPercent(value) }
    fun setPwmAlert(value: Float) = update { repository.setPwmAlertPercent(value) }
    fun setAlertsEnabled(value: Boolean) = update { repository.setAlertsEnabled(value) }
    fun setUseMetric(value: Boolean) = update { repository.setUseMetric(value) }
    fun setKeepScreenOn(value: Boolean) = update { repository.setKeepScreenOnDashboard(value) }
    fun setBridgeAutostart(value: Boolean) = update { repository.setBridgeAutostart(value) }
    fun setStandbyLowLatency(value: Boolean) = update { repository.setBridgeStandbyAdvertiseLowLatency(value) }
    fun setHudMirrorHorizontally(value: Boolean) = update { repository.setHudMirrorHorizontally(value) }

    fun startLearningRingKey() {
        RingKeyLearner.startListening()
    }

    fun stopLearningRingKey() {
        RingKeyLearner.stopListening()
    }

    fun resetRingKey() = update {
        RingKeyLearner.stopListening()
        repository.setRingKeyCode(null)
    }

    data class BondedDevice(val name: String, val address: String)

    @SuppressLint("MissingPermission")
    fun getBondedBluetoothDevices(): List<BondedDevice> {
        return runCatching {
            val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            adapter?.bondedDevices?.map { device ->
                BondedDevice(
                    name = runCatching { device.name.orEmpty() }.getOrDefault("").ifEmpty { "Bluetooth Device" },
                    address = device.address,
                )
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun setPreferredGlassesMac(mac: String?) = update {
        repository.setPreferredGlassesMac(mac)
    }

    val approvedGlasses: StateFlow<List<ApprovedGlasses>> = ApprovedGlassesStore.itemsFlow

    fun removeApprovedGlasses(item: ApprovedGlasses) {
        ApprovedGlassesStore.remove(appContext, item)
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        backupManager.exportData(out)
                    } ?: error("Failed to open output stream")
                }
                _backupEvent.emit(BackupUiEvent.ExportSuccess(count))
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Export failed", e)
                _backupEvent.emit(BackupUiEvent.ExportError(e.localizedMessage ?: "Export failed"))
            }
        }
    }

    fun importBackup(uri: Uri, replaceAll: Boolean) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        backupManager.importData(input, replaceAll)
                    } ?: error("Failed to open input stream")
                }
                _backupEvent.emit(BackupUiEvent.ImportSuccess(result))
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Import failed", e)
                _backupEvent.emit(BackupUiEvent.ImportError(e.localizedMessage ?: "Import failed"))
            }
        }
    }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
