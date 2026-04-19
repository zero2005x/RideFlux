/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.WheelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Screen state exposed to the device-scan Compose UI.
 */
data class ScannerUiState(
    val isScanning: Boolean = false,
    val devices: List<DiscoveredWheel> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * ViewModel that drives the device-scan screen.
 *
 * Scanning is ref-counted by
 * [com.rideflux.domain.repository.WheelRepository.scan]: collection
 * starts it, cancellation stops it. [startScan] / [stopScan] manage
 * the single collection job tied to [viewModelScope], so the scanner
 * shuts down automatically when the user leaves the screen.
 */
@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val wheelRepository: WheelRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    /**
     * Start (or re-start) a BLE scan. Idempotent: calling [startScan]
     * while a scan is already in flight has no effect.
     */
    fun startScan() {
        if (scanJob?.isActive == true) return
        _uiState.value = _uiState.value.copy(
            isScanning = true,
            errorMessage = null,
        )
        scanJob = wheelRepository.scan()
            .onEach { list ->
                _uiState.value = _uiState.value.copy(devices = list)
            }
            .catch { t ->
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    errorMessage = t.message ?: t.javaClass.simpleName,
                )
            }
            .launchIn(viewModelScope)
    }

    /**
     * Stop an in-flight scan. Safe to call even when no scan is
     * active. Clears [ScannerUiState.errorMessage] so the next
     * [startScan] begins with a fresh slate.
     */
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _uiState.value = _uiState.value.copy(isScanning = false)
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        scanJob = null
    }
}
