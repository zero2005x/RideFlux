package com.wheellog.next.domain.model

/**
 * Compact HUD payload sent from phone to Rokid glasses.
 * Kept small (fits within a single BLE characteristic write, ~20 bytes).
 */
data class HudPayload(
    val speedKmh: Float,
    val batteryPercent: Int,
    val temperatureC: Float,
    val alertFlags: Set<AlertFlag>,
    val useMetric: Boolean = true,
)
