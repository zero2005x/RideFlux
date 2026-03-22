package com.wheellog.next.feature.dashboard

import com.wheellog.next.domain.model.AlertFlag
import com.wheellog.next.domain.model.ConnectionState

/** User intents for the dashboard screen. */
sealed interface DashboardIntent {
    data object ToggleConnection : DashboardIntent
    data object NavigateToScan : DashboardIntent
    data object NavigateToSettings : DashboardIntent
    data object ToggleTripRecording : DashboardIntent
}

/** UI state for the dashboard. */
data class DashboardState(
    val connectionState: ConnectionState = ConnectionState.IDLE,
    val speedKmh: Float = 0f,
    val batteryPercent: Int = 0,
    val voltageV: Float = 0f,
    val temperatureC: Float = 0f,
    val totalDistanceKm: Float = 0f,
    val tripDistanceKm: Float = 0f,
    val currentA: Float = 0f,
    val alertFlags: Set<AlertFlag> = emptySet(),
    val useMetricUnits: Boolean = true,
    val isRecordingTrip: Boolean = false,
    val overspeedThresholdKmh: Float = 30f,
)

/** One-shot side effects for the dashboard. */
sealed interface DashboardEffect {
    data object NavigateToScan : DashboardEffect
    data object NavigateToSettings : DashboardEffect
    data class ShowAlert(val flag: AlertFlag) : DashboardEffect
}
