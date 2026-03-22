package com.wheellog.next.hud

/**
 * Decoded HUD data received from the phone's GATT Server.
 */
data class HudData(
    val speedKmh: Float = 0f,
    val batteryPercent: Int = 0,
    val temperatureC: Float = 0f,
    val alertMask: Int = 0,
    val useMetric: Boolean = true,
) {
    val hasOverspeed: Boolean get() = (alertMask and 0x01) != 0
    val hasLowBattery: Boolean get() = (alertMask and 0x02) != 0
    val hasHighTemperature: Boolean get() = (alertMask and 0x04) != 0
    val hasTiltBack: Boolean get() = (alertMask and 0x08) != 0
    val hasAnyAlert: Boolean get() = alertMask != 0
}
