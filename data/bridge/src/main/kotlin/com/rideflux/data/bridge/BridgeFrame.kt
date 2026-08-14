/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

/**
 * Wire-format snapshot relayed from the phone to the glasses over
 * the BLE GATT bridge. Mirrors only the subset of
 * [com.rideflux.domain.telemetry.WheelTelemetry] that the HUD
 * actually renders, plus a handful of non-telemetry signals (signal
 * quality, stale flag, phone battery) so the glasses can stay 100 %
 * passive and never need to talk to the wheel directly.
 *
 * Keep this stable; bump [BridgeProtocol.PROTOCOL_VERSION] for any
 * breaking change.
 */
data class BridgeFrame(
    /** Wall-clock millis at which the source frame was decoded on the phone. */
    val timestampMillis: Long,
    /** Magnitude speed in km/h, or null if no frame yet. */
    val speedKmh: Float?,
    /** Vehicle (wheel) battery percent 0..100, or null. */
    val vehicleBatteryPercent: Float?,
    /** Phone battery percent 0..100, or null. */
    val phoneBatteryPercent: Int?,
    /** Pack voltage in volts, or null. */
    val voltageV: Float?,
    /** Trip distance in metres since session start, or null. */
    val tripDistanceMetres: Int?,
    /** Trip duration in seconds since session start, or null. */
    val tripDurationSeconds: Long?,
    /** Coarse signal bucket — see [SignalLevel]. */
    val signal: SignalLevel,
    /** True when the underlying telemetry frame is older than the staleness threshold. */
    val stale: Boolean,
    /** True when the wheel link is connected & the handshake completed. */
    val ready: Boolean,
) {
    init {
        // 0 is reserved for the EMPTY sentinel below.
        require(timestampMillis >= 0L) { "timestampMillis must be >= 0" }
        // isFinite() as well as the range checks: NaN is rejected by the
        // comparisons already, but POSITIVE_INFINITY satisfies `>= 0f`
        // and would be encoded as a plausible saturated value.
        require(speedKmh == null || (speedKmh.isFinite() && speedKmh >= 0f)) {
            "speedKmh must be finite and non-negative"
        }
        // voltageV had no guard at all, so a non-finite pack voltage
        // reached the wire encoder and the HUD readout untouched.
        require(voltageV == null || (voltageV.isFinite() && voltageV >= 0f)) {
            "voltageV must be finite and non-negative"
        }
        require(
            vehicleBatteryPercent == null ||
                (vehicleBatteryPercent.isFinite() && vehicleBatteryPercent in 0f..100f),
        ) {
            "vehicleBatteryPercent must be finite and 0..100 or null"
        }
        require(phoneBatteryPercent == null || phoneBatteryPercent in 0..100) {
            "phoneBatteryPercent must be 0..100 or null"
        }
        require(tripDistanceMetres == null || tripDistanceMetres >= 0) {
            "tripDistanceMetres must be non-negative"
        }
        require(tripDurationSeconds == null || tripDurationSeconds >= 0L) {
            "tripDurationSeconds must be non-negative"
        }
    }

    companion object {
        /** Empty placeholder — useful as initialValue for StateFlows. */
        val EMPTY: BridgeFrame = BridgeFrame(
            timestampMillis = 0L,
            speedKmh = null,
            vehicleBatteryPercent = null,
            phoneBatteryPercent = null,
            voltageV = null,
            tripDistanceMetres = null,
            tripDurationSeconds = null,
            signal = SignalLevel.NONE,
            stale = false,
            ready = false,
        )
    }
}

/** Coarse signal bucket — wire encoding 0/1/2 in the frame header. */
enum class SignalLevel(val wire: Int) {
    NONE(0),
    WEAK(1),
    GOOD(2),
    ;

    companion object {
        /**
         * Map a wire byte back to a [SignalLevel], or `null` for an
         * unrecognised value so the decoder can reject the frame as a
         * protocol/version error rather than silently degrading.
         */
        fun fromWire(b: Int): SignalLevel? = entries.firstOrNull { it.wire == b }
    }
}
