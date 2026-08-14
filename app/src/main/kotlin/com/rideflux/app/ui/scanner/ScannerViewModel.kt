/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.scanner

import android.util.Log
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
import kotlinx.coroutines.flow.onCompletion
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
    private var scanGeneration: Long = 0L

    /**
     * Start (or re-start) a BLE scan. Idempotent: calling [startScan]
     * while a scan is already in flight has no effect.
     */
    fun startScan() {
        if (scanJob?.isActive == true) {
            Log.i(TAG, "startScan ignored — a scan is already running")
            return
        }
        // Logged before anything can fail. The repository logs when the
        // platform scan actually starts, so the two lines together say
        // whether a missing scan was a UI problem or a BLE one; with
        // only the repository line, "user never tapped" and "tapped but
        // startScan threw" are indistinguishable in a bug report.
        Log.i(TAG, "startScan requested")
        val generation = ++scanGeneration
        _uiState.value = _uiState.value.copy(
            isScanning = true,
            errorMessage = null,
        )
        val scanFlow = try {
            wheelRepository.scan()
        } catch (t: Throwable) {
            Log.e(TAG, "scan() threw before collection", t)
            if (generation == scanGeneration) {
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    errorMessage = t.message ?: t.javaClass.simpleName,
                )
            }
            return
        }
        scanJob = scanFlow
            .onEach { list ->
                if (generation == scanGeneration) {
                    _uiState.value = _uiState.value.copy(devices = list)
                }
            }
            .catch { t ->
                Log.e(TAG, "scan flow failed: ${t.message}", t)
                if (generation == scanGeneration) {
                    _uiState.value = _uiState.value.copy(
                        isScanning = false,
                        errorMessage = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
            .onCompletion {
                if (generation == scanGeneration) {
                    _uiState.value = _uiState.value.copy(isScanning = false)
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Stop an in-flight scan. Safe to call even when no scan is
     * active. Clears [ScannerUiState.errorMessage] so the next
     * [startScan] begins with a fresh slate.
     */
    fun stopScan() {
        Log.i(TAG, "stopScan requested")
        scanJob?.cancel()
        scanJob = null
        scanGeneration += 1
        _uiState.value = _uiState.value.copy(
            isScanning = false,
            errorMessage = null,
        )
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        scanJob = null
    }

    private companion object {
        const val TAG = "RideFlux/BLE"
    }
}
