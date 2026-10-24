/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.scanner

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.garage.VehicleCalibration
import com.rideflux.domain.garage.VehicleProfile
import com.rideflux.domain.garage.VehicleProfileStore
import com.rideflux.domain.garage.normalizeVehicleAddress
import com.rideflux.domain.garage.vehicleProfile
import com.rideflux.domain.repository.DiscoveredWheel
import com.rideflux.domain.repository.PlevRepository
import com.rideflux.domain.repository.ScooterRepository
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.protocol.repository.PlevRepositoryImpl
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Screen state exposed to the device-scan Compose UI.
 *
 * [savedVehicles] is the persisted garage, not the current scan list. It is
 * shown even when nothing is advertising so a rider can reconnect a known
 * vehicle (and so a scooter profile restored in a fresh process keeps its
 * category).
 */
data class ScannerUiState(
    val isScanning: Boolean = false,
    val devices: List<DiscoveredWheel> = emptyList(),
    val scooters: List<ScooterDevice> = emptyList(),
    val savedVehicles: List<VehicleProfile> = emptyList(),
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
 *
 * Selecting a device also records/updates a persisted [VehicleProfile]
 * (see [recordSelection] and [recordScooterSelection]). That is what makes
 * the family/category survive a process restart and feeds the BLE
 * repository's pre-connect hint.
 */
@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val wheelRepository: WheelRepository,
    private val scooterRepository: ScooterRepository,
    private val vehicleProfiles: VehicleProfileStore = VehicleProfileStore.empty(),
    /**
     * One classification authority for discovery and typed connect. Tests may
     * omit it; the production binding is the same singleton used by the
     * Dashboard and supplied by Hilt.
     */
    private val plevRepository: PlevRepository = PlevRepositoryImpl(wheelRepository, scooterRepository),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var scanGeneration: Long = 0L

    /**
     * Latest requested value for a profile whose edit has not completed.
     * The legacy rename/family calls used to each re-read the published
     * StateFlow; while the first write was pending, the second still carried
     * the old name. This overlay is only the issue-order source for those
     * compatibility calls; the production Save uses the store's atomic
     * [VehicleProfileStore.update].
     */
    private val pendingEdits = mutableMapOf<String, VehicleProfile>()

    init {
        vehicleProfiles.profiles
            .onEach { saved -> _uiState.value = _uiState.value.copy(savedVehicles = saved) }
            .launchIn(viewModelScope)
    }

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
        val scanFlow: Flow<Pair<List<DiscoveredWheel>, List<ScooterDevice>>> = try {
            combine(
                plevRepository.scanWheels().onStart { emit(emptyList()) },
                plevRepository.scanScooters().onStart { emit(emptyList()) },
            ) { wheels, scooters ->
                wheels.filterNot { wheel -> scooters.any { it.address == wheel.address } } to scooters
            }
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
            .onEach { (wheels, scooters) ->
                if (generation == scanGeneration) {
                    _uiState.value = _uiState.value.copy(devices = wheels, scooters = scooters)
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

    /**
     * Persist the rider's selection before navigation.
     *
     * A plain tap keeps an existing explicit family; a brand choice replaces
     * it. The record is a hint for the next connect, never a substitute for
     * discovery — the BLE resolver still validates the GATT table.
     */
    fun recordSelection(selection: ScannerSelection) {
        val address = selection.device.address
        val selectedFamily = when (selection) {
            is ScannerSelection.Brand -> selection.family
            is ScannerSelection.Scanned -> selection.device.family
        }
        viewModelScope.launch {
            // Merge inside the store's transaction so a pending Save cannot be
            // rebuilt from an older published snapshot. Only selection fields
            // change here; alias, alerts, calibration, notes and verified
            // identity stay owned by the latest record.
            val updated = vehicleProfiles.update(address) { current ->
                current.copy(
                    category = PlevCategory.WHEEL,
                    family = if (selection is ScannerSelection.Brand) {
                        selection.family
                    } else {
                        current.family ?: selectedFamily
                    },
                )
            }
            if (updated == null) {
                vehicleProfiles.upsert(
                    vehicleProfile(
                        address = address,
                        category = PlevCategory.WHEEL,
                        family = selectedFamily,
                        displayName = selection.device.displayName ?: address,
                        // Advertisement text is not verified model identity.
                        modelName = null,
                        transportHint = null,
                    ),
                )
            }
        }
    }

    fun recordScooterSelection(scooter: ScooterDevice) {
        val hint = scooter.model.trim()
            .takeIf { it.isNotEmpty() && it != "Ninebot/Xiaomi Scooter" }
        viewModelScope.launch {
            val updated = vehicleProfiles.update(scooter.address) { current ->
                current.copy(
                    category = PlevCategory.SCOOTER,
                    family = null,
                    // Classifier/name hint chooses a transport profile only.
                    // It is never copied into modelName or identityEvidence.
                    transportHint = current.transportHint ?: hint,
                )
            }
            if (updated == null) {
                vehicleProfiles.upsert(
                    vehicleProfile(
                        address = scooter.address,
                        category = PlevCategory.SCOOTER,
                        family = null,
                        displayName = scooter.model.ifBlank { scooter.address },
                        modelName = null,
                        transportHint = hint,
                    ),
                )
            }
        }
    }

    /**
     * Save the whole editable profile in one atomic store update.
     *
     * This is the production Save entry. Name, family, per-vehicle alert
     * override and display calibration are one record, so a second writer
     * cannot resurrect an older name/family from a stale UI snapshot.
     */
    fun saveVehicle(
        address: String,
        name: String,
        family: WheelFamily?,
        alertThresholds: AlertThresholds?,
        calibration: VehicleCalibration,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val norm = normalizeVehicleAddress(address)
        val base = pendingEdits[norm] ?: existingProfile(address) ?: return
        val optimistic = base.copy(
            displayName = trimmed,
            family = family,
            alertThresholds = alertThresholds,
            calibration = calibration,
        )
        pendingEdits[norm] = optimistic
        viewModelScope.launch {
            try {
                vehicleProfiles.update(address) { current ->
                    current.copy(
                        displayName = trimmed,
                        family = family,
                        alertThresholds = alertThresholds,
                        calibration = calibration,
                    )
                }
            } finally {
                if (pendingEdits[norm] == optimistic) pendingEdits.remove(norm)
            }
        }
    }

    /** Reset only the per-vehicle alert override and display calibration. */
    fun resetVehicleOverrides(address: String) {
        val norm = normalizeVehicleAddress(address)
        val base = pendingEdits[norm] ?: existingProfile(address) ?: return
        val optimistic = base.copy(alertThresholds = null, calibration = VehicleCalibration())
        pendingEdits[norm] = optimistic
        viewModelScope.launch {
            try {
                vehicleProfiles.update(address) { current ->
                    current.copy(alertThresholds = null, calibration = VehicleCalibration())
                }
            } finally {
                if (pendingEdits[norm] == optimistic) pendingEdits.remove(norm)
            }
        }
    }

    /**
     * Compatibility wrappers retained for callers/tests that edit one field.
     * They merge through [pendingEdits] instead of each capturing the same
     * published snapshot, so rename-then-family cannot lose the new name.
     * The production dialog uses [saveVehicle] and its atomic store update.
     */
    fun renameVehicle(address: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val norm = normalizeVehicleAddress(address)
        val existing = pendingEdits[norm] ?: existingProfile(address) ?: return
        val updated = existing.copy(displayName = trimmed)
        pendingEdits[norm] = updated
        viewModelScope.launch {
            try {
                vehicleProfiles.upsert(updated)
            } finally {
                if (pendingEdits[norm] == updated) pendingEdits.remove(norm)
            }
        }
    }

    /** Update the explicit family of a saved vehicle (rider correction). */
    fun setVehicleFamily(address: String, family: WheelFamily?) {
        val norm = normalizeVehicleAddress(address)
        val existing = pendingEdits[norm] ?: existingProfile(address) ?: return
        val updated = existing.copy(family = family)
        pendingEdits[norm] = updated
        viewModelScope.launch {
            try {
                vehicleProfiles.upsert(updated)
            } finally {
                if (pendingEdits[norm] == updated) pendingEdits.remove(norm)
            }
        }
    }

    /** Remove one saved vehicle; other profiles and scan results are untouched. */
    fun removeVehicle(address: String) {
        viewModelScope.launch { vehicleProfiles.remove(address) }
    }

    private fun existingProfile(address: String): VehicleProfile? =
        _uiState.value.savedVehicles.firstOrNull {
            it.address.equals(address, ignoreCase = true)
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
