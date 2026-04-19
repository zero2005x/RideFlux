/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.telemetry.RideMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * UI projection consumed by [HudScreen]. Mirrors the essentials from
 * :app's `DashboardUiState` but stays intentionally narrow — the HUD
 * only renders speed, battery, voltage and ride mode.
 */
data class HudUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val speedKmh: Float? = null,
    val voltageV: Float? = null,
    val batteryPercent: Float? = null,
    val headlightOn: Boolean = false,
    val rideMode: RideMode? = null,
    /** `true` when no MAC was provided via intent extras. */
    val awaitingTarget: Boolean = false,
)

/**
 * Hilt-injected ViewModel for the standalone HUD APK.
 *
 * The activity places the target MAC into its intent extras under
 * [KEY_MAC]; Hilt's SavedStateHandle binding automatically exposes
 * those extras, so the ViewModel can read the address without a nav
 * graph.
 *
 * Connection lifecycle mirrors :app's DashboardViewModel: connect
 * lazily via a [Deferred], project every telemetry StateFlow onto a
 * single [uiState], close the connection exactly once on [onCleared].
 * When no MAC is provided, [uiState] parks on a non-connecting
 * sentinel so the UI can render instructions.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HudViewModel @Inject constructor(
    private val wheelRepository: WheelRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Nullable — the HUD activity can be launched with no extras. */
    private val targetAddress: String? = savedStateHandle[KEY_MAC]

    private val connectionAsync: Deferred<WheelConnection>? =
        targetAddress?.let { mac ->
            viewModelScope.async {
                withContext(Dispatchers.IO) {
                    wheelRepository.connect(address = mac, expectedFamily = null)
                }
            }
        }

    val uiState: StateFlow<HudUiState> =
        if (connectionAsync == null) {
            // No MAC: park on a sentinel.
            flowOf(HudUiState(awaitingTarget = true))
        } else {
            flow { emit(connectionAsync.await()) }.flatMapLatest { conn ->
                kotlinx.coroutines.flow.combine(
                    conn.state,
                    conn.speedKmh,
                    conn.voltageV,
                    conn.batteryPercent,
                    conn.telemetry.map { it.rideMode },
                ) { state, spd, v, bp, mode ->
                    HudUiState(
                        connectionState = state,
                        // Same abs() treatment as the phone
                        // dashboard — riders read magnitude, not
                        // direction.
                        speedKmh = spd?.let { kotlin.math.abs(it) },
                        voltageV = v,
                        batteryPercent = bp
                            ?: if (state == ConnectionState.Ready) 0f else null,
                        rideMode = mode,
                        headlightOn = false,
                        awaitingTarget = false,
                    )
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
            initialValue = HudUiState(awaitingTarget = targetAddress == null),
        )

    override fun onCleared() {
        super.onCleared()
        val handle = connectionAsync ?: return
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            try {
                handle.await().close()
            } catch (_: Throwable) {
                // Best-effort; already-closed is acceptable.
            }
        }
    }

    companion object {
        /**
         * SavedStateHandle / intent-extra key for the target BLE MAC.
         * Must match [HudActivity.EXTRA_MAC]; kept as a separate
         * constant so callers can depend on either without a cycle.
         */
        const val KEY_MAC: String = "mac"
    }
}
