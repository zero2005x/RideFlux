/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi2

import com.rideflux.protocol.bytes.ByteReader

/**
 * Vendor-static real-time layout for the Inmotion V11Y 74-byte frame.
 *
 * Evidence: the reconstructed `V11yRealtimeInfo.fromBytes` in the Inmotion
 * 1.7.00 arm64-v8a Dart AOT (`libapp.so` SHA-256
 * `feaaf7cf7fde39ba83a344307004e2071d0358948db24649a5e4b6920114b550`) loads
 * each field at the offset below, and its `toMap` publishes the vendor keys
 * `bus_voltage`, `bus_current`, `speed` and `battery_percentage`. The
 * per-load locators and keys are recorded in
 * `handoffs/20261024-inmotion-i2-layout-successor4-20261025T0005/`
 * (artifact `v11y_realtime_info.dart`, SHA-256
 * `12b7548a86f3872b49fc98e660291c8cd8f97b68c34089f42dcde41d877a2e85`) and
 * accepted by
 * `handoffs/20261025-inmotion-i2-layout-successor4-acceptance/ACCEPTANCE.md`.
 * The `/100` `bus_voltage` scale comes from the vendor
 * `VehicleRealtimeInfo.fromMap`.
 *
 * This is **vendor-static** evidence from a shipped artifact: it is not a
 * wire capture, and the vendor schema does not establish the physical units
 * of these raw values. The product names and units below are product
 * conventions joined to the vendor keys and divisors.
 *
 * Only fields whose scale is explicitly established are exposed:
 *
 * | offset | width | field              | scale        |
 * |-------:|------:|--------------------|--------------|
 * | 0x00   | s16   | bus voltage        | / 100        |
 * | 0x02   | s16   | signed bus current | / 100        |
 * | 0x08   | s16   | speed              | / 100 km/h   |
 * | 0x22   | u16   | battery ratio      | / 10000 → %  |
 *
 * The remaining documented slots stay `null` instead of being copied from
 * V11 or guessed: `0x06` is the charge current, `0x1a` is the
 * speeding/braking angle, `0x1c` is `mileage` with no established scale,
 * and the temperature fields use a `+80` bias whose unit is not established.
 */
internal data class InmotionI2RealtimeV11Y(
    val voltageV: Double,
    val currentA: Double,
    val speedKmh: Double,
    val batteryPercent: Float?,
) {
    companion object {
        /** Vendor minimum body length for the 74-guard Lorin real-time frame. */
        const val MIN_DATA_SIZE: Int = 74

        const val OFFSET_BUS_VOLTAGE: Int = 0x00
        const val OFFSET_BUS_CURRENT: Int = 0x02

        /** Vendor `speed` at 0x08; `0x06` is the unexposed charge current. */
        const val OFFSET_SPEED: Int = 0x08

        /** Vendor `battery_percentage` at 0x22; `0x1c` is `mileage`. */
        const val OFFSET_BATTERY_RATIO: Int = 0x22

        /** Vendor `bus_voltage` divisor from `VehicleRealtimeInfo.fromMap`. */
        const val VOLTAGE_DIVISOR: Double = 100.0

        /** Vendor `bus_current` / `speed` divisor. */
        const val CURRENT_DIVISOR: Double = 100.0
        const val SPEED_DIVISOR: Double = 100.0

        /** Vendor battery ratio divisor: 5000 → 0.5 ratio → 50 %. */
        const val BATTERY_DIVISOR: Int = 10000

        fun parse(data: ByteArray): InmotionI2RealtimeV11Y {
            require(data.size >= MIN_DATA_SIZE) {
                "V11Y DATA too short: ${data.size} < $MIN_DATA_SIZE"
            }
            val batteryRaw = ByteReader.u16LE(data, OFFSET_BATTERY_RATIO)
            return InmotionI2RealtimeV11Y(
                voltageV = ByteReader.s16LE(data, OFFSET_BUS_VOLTAGE) / VOLTAGE_DIVISOR,
                currentA = ByteReader.s16LE(data, OFFSET_BUS_CURRENT) / CURRENT_DIVISOR,
                speedKmh = ByteReader.s16LE(data, OFFSET_SPEED) / SPEED_DIVISOR,
                // Out-of-range ratios are not evidence; publish null rather
                // than a clamp that would look like a real measurement.
                batteryPercent = if (batteryRaw in 0..BATTERY_DIVISOR) {
                    batteryRaw.toFloat() / (BATTERY_DIVISOR / 100f)
                } else {
                    null
                },
            )
        }
    }
}
