/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.telemetry

/**
 * Family-agnostic telemetry snapshot.
 *
 * A single value of this class represents the best available view of
 * the wheel at one instant, assembled by the
 * [com.rideflux.domain.connection.WheelConnection] from whatever
 * family-specific frames have been decoded so far. Fields for which
 * no frame has yet been received are `null`; fields that a given
 * family never reports remain `null` for the lifetime of the
 * connection.
 *
 * All physical quantities are in SI-style canonical units:
 * - voltages in volts,
 * - currents in amperes,
 * - distances in metres,
 * - speeds in kilometres per hour (the ride-industry convention),
 * - temperatures in degrees Celsius,
 * - angles in degrees.
 *
 * Family-specific scalings (e.g. Inmotion's per-model `F` speed
 * constant in §3.5.2, Family-G `0.1 %` PWM units, etc.) are applied
 * by the codec **before** populating this snapshot so that consumers
 * never need to know the wire format.
 */
data class WheelTelemetry(
    /** Monotonic wall-clock stamp of the most recent contributing frame, ms. */
    val timestampMillis: Long,

    // ---- Kinematics -----------------------------------------------------
    val speedKmh: Float? = null,
    val tripDistanceMetres: Int? = null,
    val totalDistanceMetres: Long? = null,
    val pitchAngleDegrees: Float? = null,
    val rollAngleDegrees: Float? = null,

    // ---- Power train ----------------------------------------------------
    val voltageV: Float? = null,
    val currentA: Float? = null,
    val phaseCurrentA: Float? = null,
    val pwmPercent: Float? = null,

    // ---- Battery --------------------------------------------------------
    /** Best-estimate battery state of charge, 0..100. */
    val batteryPercent: Float? = null,
    val batteryVoltageV: Float? = null,
    val chargingState: ChargingState? = null,

    // ---- Thermals -------------------------------------------------------
    val mosTemperatureC: Float? = null,
    val motorTemperatureC: Float? = null,
    val boardTemperatureC: Float? = null,
    val batteryTemperatureC: Float? = null,
    val imuTemperatureC: Float? = null,

    // ---- Mode / state ---------------------------------------------------
    val rideMode: RideMode? = null,
    val workMode: String? = null,
    val faults: Set<WheelFault> = emptySet(),
) {
    companion object {
        /** Empty snapshot used as the `initialValue` of the telemetry StateFlow. */
        val EMPTY: WheelTelemetry = WheelTelemetry(timestampMillis = 0L)
    }
}

/** Charging-circuit high-level state, when reported. */
enum class ChargingState { NOT_CONNECTED, CHARGING, FULLY_CHARGED, FAULT }

/**
 * Family-agnostic ride-mode view. The [code] is the family's raw
 * integer code (so feature code can round-trip back to a setter) and
 * [label] is a short human string suitable for UI. Families that
 * have no ride-mode concept set [code] to `-1`.
 */
data class RideMode(val code: Int, val label: String)

/**
 * Canonical fault identifier. Codecs map family-specific fault bits
 * to this enum; bits with no canonical mapping emit [Unknown]
 * carrying the raw bit number for diagnostics.
 */
sealed class WheelFault {
    data object OverVoltage : WheelFault()
    data object UnderVoltage : WheelFault()
    data object OverCurrent : WheelFault()
    data object OverSpeed : WheelFault()
    data object MosOverTemperature : WheelFault()
    data object MotorOverTemperature : WheelFault()
    data object BatteryOverTemperature : WheelFault()
    data object BoardOverTemperature : WheelFault()
    data object MotorHallSensor : WheelFault()
    data object ImuSensor : WheelFault()
    data object MotorBlocked : WheelFault()
    data object LowBattery : WheelFault()
    data object RiskBehaviour : WheelFault()
    data class Unknown(val domain: String, val code: Int) : WheelFault()
}
