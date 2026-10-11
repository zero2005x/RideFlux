/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.telemetry

/**
 * A [WheelTelemetry] field that a codec can refresh, leave unchanged, or withdraw.
 *
 * A null on a telemetry snapshot still means "this frame did not mention the field"
 * ([WheelTelemetryMerge] keeps the previous value). Putting the field in an
 * invalidation set means the previous value is no longer evidence and must become null.
 */
enum class TelemetryField {
    SPEED,
    TRIP_DISTANCE,
    TOTAL_DISTANCE,
    PITCH,
    ROLL,
    VOLTAGE,
    CURRENT,
    PHASE_CURRENT,
    PWM,
    BATTERY_PERCENT,
    BATTERY_VOLTAGE,
    CHARGING_STATE,
    MOS_TEMPERATURE,
    MOTOR_TEMPERATURE,
    BOARD_TEMPERATURE,
    BATTERY_TEMPERATURE,
    IMU_TEMPERATURE,
    RIDE_MODE,
    WORK_MODE,
    FAULTS,
    BMS_STATUS,
}
