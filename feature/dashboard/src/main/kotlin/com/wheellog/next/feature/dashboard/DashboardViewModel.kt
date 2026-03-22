package com.wheellog.next.feature.dashboard

import androidx.lifecycle.viewModelScope
import com.wheellog.next.core.ui.mvi.ComposeViewModel
import com.wheellog.next.domain.model.AlertFlag
import com.wheellog.next.domain.model.ConnectionState
import com.wheellog.next.domain.repository.EucRepository
import com.wheellog.next.domain.repository.PreferencesRepository
import com.wheellog.next.domain.usecase.ObserveTelemetryUseCase
import com.wheellog.next.domain.usecase.TripRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val observeTelemetry: ObserveTelemetryUseCase,
    private val eucRepository: EucRepository,
    private val preferencesRepository: PreferencesRepository,
    private val tripRecorder: TripRecorder,
) : ComposeViewModel<DashboardIntent, DashboardState, DashboardEffect>(DashboardState()) {

    init {
        observeConnectionState()
        observeTelemetryStream()
        observePreferences()
        observeTripRecordingState()
    }

    override fun handleIntent(intent: DashboardIntent) {
        when (intent) {
            DashboardIntent.ToggleConnection -> toggleConnection()
            DashboardIntent.NavigateToScan -> emitEffect(DashboardEffect.NavigateToScan)
            DashboardIntent.NavigateToSettings -> emitEffect(DashboardEffect.NavigateToSettings)
            DashboardIntent.ToggleTripRecording -> toggleTripRecording()
        }
    }

    private fun observeConnectionState() {
        eucRepository.observeConnectionState()
            .onEach { connState -> updateState { copy(connectionState = connState) } }
            .launchIn(viewModelScope)
    }

    private fun observeTelemetryStream() {
        observeTelemetry()
            .onEach { telemetry ->
                val previousAlerts = state.value.alertFlags
                updateState {
                    copy(
                        speedKmh = telemetry.speedKmh,
                        batteryPercent = telemetry.batteryPercent,
                        voltageV = telemetry.voltageV,
                        temperatureC = telemetry.temperatureC,
                        totalDistanceKm = telemetry.totalDistanceKm,
                        tripDistanceKm = telemetry.tripDistanceKm,
                        currentA = telemetry.currentA,
                        alertFlags = telemetry.alertFlags.applyOverspeedThreshold(
                            telemetry.speedKmh,
                            state.value.overspeedThresholdKmh,
                        ),
                    )
                }
                // Feed telemetry to trip recorder
                tripRecorder.update(telemetry)
                // Emit newly triggered alerts as one-shot effects
                val newAlerts = telemetry.alertFlags - previousAlerts
                newAlerts.forEach { emitEffect(DashboardEffect.ShowAlert(it)) }
            }
            .launchIn(viewModelScope)
    }

    private fun observePreferences() {
        preferencesRepository.useMetricUnits()
            .onEach { metric -> updateState { copy(useMetricUnits = metric) } }
            .launchIn(viewModelScope)

        preferencesRepository.overspeedThresholdKmh()
            .onEach { kmh -> updateState { copy(overspeedThresholdKmh = kmh) } }
            .launchIn(viewModelScope)
    }

    private fun observeTripRecordingState() {
        tripRecorder.isRecording
            .onEach { recording -> updateState { copy(isRecordingTrip = recording) } }
            .launchIn(viewModelScope)
    }

    private fun toggleTripRecording() {
        viewModelScope.launch {
            if (tripRecorder.isRecording.value) {
                tripRecorder.stop()
            } else {
                tripRecorder.start(
                    currentTripDistance = state.value.tripDistanceKm,
                )
            }
        }
    }

    private fun toggleConnection() {
        viewModelScope.launch {
            if (state.value.connectionState == ConnectionState.CONNECTED) {
                eucRepository.disconnect()
            } else {
                emitEffect(DashboardEffect.NavigateToScan)
            }
        }
    }

    /** Add or remove [AlertFlag.OVERSPEED] based on the user-configured threshold. */
    private fun Set<AlertFlag>.applyOverspeedThreshold(
        speedKmh: Float,
        thresholdKmh: Float,
    ): Set<AlertFlag> =
        if (kotlin.math.abs(speedKmh) >= thresholdKmh) this + AlertFlag.OVERSPEED
        else this - AlertFlag.OVERSPEED
}
