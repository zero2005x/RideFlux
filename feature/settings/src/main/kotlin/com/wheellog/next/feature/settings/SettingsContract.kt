package com.wheellog.next.feature.settings

/** User intents for the settings screen. */
sealed interface SettingsIntent {
    data class SetMetricUnits(val useMetric: Boolean) : SettingsIntent
    data class SetOverspeedThreshold(val kmh: Float) : SettingsIntent
}

/** UI state for the settings screen. */
data class SettingsState(
    val useMetricUnits: Boolean = true,
    val overspeedThresholdKmh: Float = 30f,
)

/** One-shot side effects for the settings screen. */
sealed interface SettingsEffect {
    data class ShowSaved(val message: String) : SettingsEffect
}
