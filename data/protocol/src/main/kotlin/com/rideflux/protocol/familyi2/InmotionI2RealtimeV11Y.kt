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
 * Evidence: the vendor Dart AOT `V11YRealtimeInfo.fromBytes` shares the
 * ordered store-slot sequence and byte/opcode table of the 74-guard Lorin
 * family documented in
 * `euc-programme/findings/inmotion-agent-guide_20261013-1440-inmotion-multidevice/04-TELEMETRY-LAYOUTS-AND-UNITS.md`.
 * That is **vendor-static** evidence, not a wire capture.
 *
 * Only fields whose scale is explicitly established are exposed:
 *
 * | offset | width | field             | scale       |
 * |-------:|------:|-------------------|-------------|
 * | 0x00   | s16   | bus voltage       | / 50        |
 * | 0x02   | s16   | signed bus current| / 100       |
 * | 0x06   | s16   | speed             | / 100 km/h  |
 * | 0x1c   | u16   | battery ratio     | / 10000 → % |
 *
 * The trip `mileage` field at 0x1a has no established scale, and the
 * temperatures use a `+80` bias whose unit is not established; both remain
 * `null` instead of being copied from V11 or guessed.
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
        const val OFFSET_SPEED: Int = 0x06
        const val OFFSET_BATTERY_RATIO: Int = 0x1c

        /** Vendor `bus_voltage` divisor from `VehicleRealtimeInfo.fromMap`. */
        const val VOLTAGE_DIVISOR: Double = 50.0

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
