package com.wheellog.next.domain.repository

import com.wheellog.next.domain.model.HudPayload
import kotlinx.coroutines.flow.Flow

/**
 * HUD gateway repository — manages the BLE GATT Server that pushes
 * telemetry data to Rokid AR glasses.
 * Implemented by :feature:hud-gateway (service layer).
 */
interface HudGatewayRepository {
    /** Whether the GATT Server is currently advertising. */
    val isAdvertising: Flow<Boolean>

    /** Number of HUD clients currently connected to the GATT server. */
    val connectedDeviceCount: Flow<Int>

    /** Start BLE GATT Server advertising. */
    suspend fun startAdvertising()

    /** Stop BLE GATT Server advertising. */
    suspend fun stopAdvertising()

    /** Push a telemetry payload to connected HUD clients. */
    suspend fun pushPayload(payload: HudPayload)
}
