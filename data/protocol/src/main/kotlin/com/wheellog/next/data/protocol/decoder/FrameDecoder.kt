package com.wheellog.next.data.protocol.decoder

import com.wheellog.next.domain.model.TelemetryState

/**
 * Per-brand frame decoder contract.
 * Each brand-specific decoder receives raw BLE notification bytes
 * and returns a [TelemetryState] or null if the frame is incomplete/invalid.
 */
interface FrameDecoder {

    /**
     * Decode a BLE notification payload.
     * @param bytes Raw bytes from the BLE characteristic notification.
     * @return Parsed [TelemetryState] or null if the frame cannot be decoded.
     */
    fun decode(bytes: ByteArray): TelemetryState?
}
