package com.wheellog.next.domain.model

/**
 * Core telemetry state — unified data model parsed from all EUC brands.
 */
data class TelemetryState(
    val speedKmh: Float = 0f,
    val batteryPercent: Int = 0,
    val voltageV: Float = 0f,
    val temperatureC: Float = 0f,
    val totalDistanceKm: Float = 0f,
    val tripDistanceKm: Float = 0f,
    val currentA: Float = 0f,
    val alertFlags: Set<AlertFlag> = emptySet(),
)

enum class AlertFlag {
    OVERSPEED,
    LOW_BATTERY,
    HIGH_TEMPERATURE,
    TILT_BACK,
}
