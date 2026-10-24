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
    /**
     * Phone's request that the glasses blank the HUD.
     *
     * This is the one field that travels *as a command* rather than as
     * telemetry: it lets a ring paired to the phone hide and reveal the
     * display without the glasses needing an input device of their own.
     * It rides in a previously unused flag bit, so a phone or HUD on the
     * older build simply never sets or reads it — no version bump, and
     * mixed installs keep working.
     */
    val hudHidden: Boolean = false,
    /**
     * Which kind of vehicle produced this frame.
     *
     * [VehicleType.UNKNOWN] is the value a v2 frame decodes to: it
     * carried no type at all, so a receiver must fall back to
     * wheel-shaped rendering rather than guess.
     */
    val vehicleType: VehicleType = VehicleType.UNKNOWN,
    /**
     * Category-specific measurement at 0.1 resolution.
     *
     * A wheel puts pack current in amperes here; a scooter puts ESC frame
     * temperature in °C. Null means the category had nothing to report.
     */
    val aux1: Float? = null,
    /**
     * Category-specific small integer.
     *
     * A wheel puts PWM percent here; a scooter puts its gear. The wire
     * slot is one byte with `0xFF` reserved for "absent", so the legal
     * range stops at 254.
     */
    val aux2: Int? = null,
    /**
     * Second category-specific small integer.
     *
     * A wheel puts MOS temperature **biased by +64** here, because 0 °C is
     * a valid reading and must not collide with the absent sentinel; a
     * scooter puts throttle percent. Same 0..254 range as [aux2].
     */
    val aux3: Int? = null,
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
        require(aux1 == null || aux1.isFinite()) { "aux1 must be finite or null" }
        require(aux2 == null || aux2 in 0..MAX_AUX_CODE) {
            "aux2 must be 0..$MAX_AUX_CODE or null"
        }
        require(aux3 == null || aux3 in 0..MAX_AUX_CODE) {
            "aux3 must be 0..$MAX_AUX_CODE or null"
        }
    }

    companion object {
        /**
         * Highest value a one-byte aux slot may carry.
         *
         * The wire reserves `0xFF` for "absent", so a producer must clamp
         * or drop anything above this rather than let it collide.
         */
        const val MAX_AUX_CODE: Int = 254

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

/**
 * Which kind of vehicle a frame describes — wire encoding in flags bits 5-6.
 *
 * [UNKNOWN] is not an error: a v2 frame carried no type, so a decoder
 * that accepts a legacy frame reports it as unknown and the receiver
 * falls back to wheel-shaped rendering.
 */
enum class VehicleType(val wire: Int) {
    UNKNOWN(0),
    WHEEL(1),
    SCOOTER(2),
    BMS(3),
    ;

    companion object {
        fun fromWire(b: Int): VehicleType? = entries.firstOrNull { it.wire == b }
    }
}
