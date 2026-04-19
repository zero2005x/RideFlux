/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.telemetry

/**
 * Asynchronous, one-shot event emitted by the wheel.
 *
 * Unlike [WheelTelemetry] — which is a *latest-state* projection
 * exposed via `StateFlow` — alerts are discrete events surfaced to
 * the UI as a `SharedFlow` (no replay by default).
 *
 * The hierarchy maps family-specific alerts to a family-agnostic
 * shape whenever possible:
 *
 * - [TiltBack] covers Inmotion I1 `alertId 0x06` (§4.3.2) and the
 *   equivalent Begode / KingSong speed-alarm categories.
 * - [OverTemperature] collapses MOS/motor/battery over-temperature
 *   reports from every family.
 * - [FaultSetChanged] fires when the canonical
 *   [WheelTelemetry.faults] set transitions.
 * - [Raw] is the escape hatch for family-specific alerts without a
 *   canonical mapping (e.g. KingSong smart-BMS cell-diff).
 */
sealed class WheelAlert {
    /** Wall-clock timestamp of the originating frame, ms. */
    abstract val timestampMillis: Long

    data class TiltBack(
        override val timestampMillis: Long,
        val speedKmh: Float,
        val limit: Float,
    ) : WheelAlert()

    data class SpeedCutoff(
        override val timestampMillis: Long,
        val speedKmh: Float,
    ) : WheelAlert()

    data class LowBattery(
        override val timestampMillis: Long,
        val voltageV: Float,
    ) : WheelAlert()

    data class OverTemperature(
        override val timestampMillis: Long,
        val source: Source,
        val temperatureC: Float?,
    ) : WheelAlert() {
        enum class Source { MOS, MOTOR, BATTERY, BOARD, CPU, IMU }
    }

    data class FallDown(
        override val timestampMillis: Long,
    ) : WheelAlert()

    data class FaultSetChanged(
        override val timestampMillis: Long,
        val added: Set<WheelFault>,
        val removed: Set<WheelFault>,
    ) : WheelAlert()

    /**
     * Family-specific alert without a canonical mapping. [domain] is
     * the originating family's name (e.g. `"I1"`, `"K"`), [code] is
     * the raw alert identifier, and [payload] preserves the
     * verbatim bytes for forensic logging.
     */
    data class Raw(
        override val timestampMillis: Long,
        val domain: String,
        val code: Int,
        val payload: ByteArray,
    ) : WheelAlert() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Raw) return false
            return timestampMillis == other.timestampMillis &&
                domain == other.domain &&
                code == other.code &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var h = timestampMillis.hashCode()
            h = 31 * h + domain.hashCode()
            h = 31 * h + code
            h = 31 * h + payload.contentHashCode()
            return h
        }
    }
}
