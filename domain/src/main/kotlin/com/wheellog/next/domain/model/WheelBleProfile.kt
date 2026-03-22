package com.wheellog.next.domain.model

/**
 * BLE profile describing how to communicate with a specific EUC brand.
 * Contains the BLE service and characteristic UUIDs needed for connection.
 */
data class WheelBleProfile(
    val wheelType: WheelType,
    val serviceUuid: String,
    val notifyCharacteristicUuid: String,
    val writeCharacteristicUuid: String? = null,
)
