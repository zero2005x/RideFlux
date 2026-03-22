package com.wheellog.next.domain.protocol

import com.wheellog.next.domain.model.TelemetryState
import com.wheellog.next.domain.model.WheelBleProfile
import com.wheellog.next.domain.model.WheelType

/**
 * Abstraction over EUC brand-specific BLE protocol handling.
 * Implemented by :data:protocol, injected into :data:ble.
 */
interface ProtocolDecoder {

    /** Detect the EUC brand from the BLE advertisement name. */
    fun detectWheelType(deviceName: String?): WheelType

    /** Get the BLE profile (service/characteristic UUIDs) for a brand. */
    fun bleProfileFor(wheelType: WheelType): WheelBleProfile?

    /**
     * Decode a raw BLE notification byte array into [TelemetryState].
     * Returns null if the frame is incomplete or unrecognised.
     */
    fun decode(wheelType: WheelType, bytes: ByteArray): TelemetryState?
}
