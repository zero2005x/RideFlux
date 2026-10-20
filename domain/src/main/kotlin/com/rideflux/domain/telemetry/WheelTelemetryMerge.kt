/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.telemetry

/**
 * Merges a partial telemetry snapshot into the previous one.
 *
 * Null on [delta] means no change. A field listed in [invalidated] is cleared even
 * when [delta] also carries a value, so a withdrawn reading cannot survive one frame.
 */
object WheelTelemetryMerge {
    fun merge(
        base: WheelTelemetry,
        delta: WheelTelemetry,
        invalidated: Set<TelemetryField> = emptySet(),
        fallbackTimestampMillis: Long = 0L,
    ): WheelTelemetry {
        fun <T> pick(field: TelemetryField, incoming: T?, previous: T?): T? = when {
            field in invalidated -> null
            incoming != null -> incoming
            else -> previous
        }
        val stamped = maxOf(base.timestampMillis, delta.timestampMillis)
        return base.copy(
            timestampMillis = stamped.takeIf { it > 0L } ?: fallbackTimestampMillis,
            speedKmh = pick(TelemetryField.SPEED, delta.speedKmh, base.speedKmh),
            tripDistanceMetres = pick(TelemetryField.TRIP_DISTANCE, delta.tripDistanceMetres, base.tripDistanceMetres),
            totalDistanceMetres = pick(TelemetryField.TOTAL_DISTANCE, delta.totalDistanceMetres, base.totalDistanceMetres),
            pitchAngleDegrees = pick(TelemetryField.PITCH, delta.pitchAngleDegrees, base.pitchAngleDegrees),
            rollAngleDegrees = pick(TelemetryField.ROLL, delta.rollAngleDegrees, base.rollAngleDegrees),
            voltageV = pick(TelemetryField.VOLTAGE, delta.voltageV, base.voltageV),
            currentA = pick(TelemetryField.CURRENT, delta.currentA, base.currentA),
            phaseCurrentA = pick(TelemetryField.PHASE_CURRENT, delta.phaseCurrentA, base.phaseCurrentA),
            pwmPercent = pick(TelemetryField.PWM, delta.pwmPercent, base.pwmPercent),
            batteryPercent = pick(TelemetryField.BATTERY_PERCENT, delta.batteryPercent, base.batteryPercent),
            batteryVoltageV = pick(TelemetryField.BATTERY_VOLTAGE, delta.batteryVoltageV, base.batteryVoltageV),
            chargingState = pick(TelemetryField.CHARGING_STATE, delta.chargingState, base.chargingState),
            mosTemperatureC = pick(TelemetryField.MOS_TEMPERATURE, delta.mosTemperatureC, base.mosTemperatureC),
            motorTemperatureC = pick(TelemetryField.MOTOR_TEMPERATURE, delta.motorTemperatureC, base.motorTemperatureC),
            boardTemperatureC = pick(TelemetryField.BOARD_TEMPERATURE, delta.boardTemperatureC, base.boardTemperatureC),
            batteryTemperatureC = pick(TelemetryField.BATTERY_TEMPERATURE, delta.batteryTemperatureC, base.batteryTemperatureC),
            imuTemperatureC = pick(TelemetryField.IMU_TEMPERATURE, delta.imuTemperatureC, base.imuTemperatureC),
            rideMode = pick(TelemetryField.RIDE_MODE, delta.rideMode, base.rideMode),
            workMode = pick(TelemetryField.WORK_MODE, delta.workMode, base.workMode),
            faults = pick(TelemetryField.FAULTS, delta.faults, base.faults),
            bmsStatus = pick(TelemetryField.BMS_STATUS, delta.bmsStatus, base.bmsStatus),
        )
    }
}
