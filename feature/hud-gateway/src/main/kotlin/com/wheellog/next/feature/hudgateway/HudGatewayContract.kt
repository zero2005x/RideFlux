package com.wheellog.next.feature.hudgateway

/** User intents for the HUD gateway screen. */
sealed interface HudGatewayIntent {
    data object ToggleAdvertising : HudGatewayIntent
}

/** UI state for the HUD gateway screen. */
data class HudGatewayState(
    val isAdvertising: Boolean = false,
    val connectedDeviceName: String? = null,
    val connectedDeviceCount: Int = 0,
    val lastPushSpeedKmh: Float = 0f,
    val lastPushBatteryPercent: Int = 0,
)

/** One-shot side effects for the HUD gateway screen. */
sealed interface HudGatewayEffect {
    data class ShowError(val message: String) : HudGatewayEffect
}
