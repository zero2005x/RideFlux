/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.wheel

/**
 * Immutable identification block resolved during the family-specific
 * bootstrap handshake (`PROTOCOL_SPEC.md` §9).
 *
 * [address] and [family] are always present. [modelName] is always
 * resolved — codecs fall back to the family marketing name when the
 * device does not advertise one — while [serialNumber] and
 * [firmwareVersion] are best-effort and may be `null` when a model
 * does not report them.
 *
 * Note that [equals] / [hashCode] include [modelName], [serialNumber]
 * and [firmwareVersion]; because firmware updates or label changes
 * alter these fields, do NOT use this object as a cross-session map
 * key — key by [address] instead.
 *
 * @property address Stable device identifier exposed by the BLE layer
 *   (MAC on Android, UUID on iOS). Treated as opaque by the domain.
 * @property family Wire-protocol family, as detected.
 * @property modelName Marketing name, e.g. `"V12 PRO"`, `"Sherman S"`.
 * @property serialNumber Manufacturer serial, when reported.
 * @property firmwareVersion Main-board firmware, format is
 *   family-specific (e.g. V-family `"001.0.58"` per §8.4, I2 `"M.m.p"`
 *   per §9.5).
 */
data class WheelIdentity(
    val address: String,
    val family: WheelFamily,
    val modelName: String,
    val serialNumber: String? = null,
    val firmwareVersion: String? = null,
) {
    init {
        // [address] and [modelName] are documented as always present.
        // Enforce it here so a malformed advertisement cannot silently
        // corrupt link-layer lookups or render a blank UI label.
        require(address.isNotBlank()) { "address must not be blank" }
        require(modelName.isNotBlank()) { "modelName must not be blank" }
    }
}

/**
 * Static per-connection feature matrix. Populated immediately after
 * identification; values do not change for the session.
 *
 * A feature-level UI consults this struct *before* enabling a
 * control. A [com.rideflux.domain.command.WheelCommand] dispatched to
 * a connection whose capability flag is `false` will fail with
 * [com.rideflux.domain.command.CommandOutcome.Unsupported] rather
 * than silently dropping on the wire.
 */
data class WheelCapabilities(
    val headlight: Boolean,
    val horn: Boolean,
    val beep: Boolean,
    val ledStrip: Boolean,
    val decorativeLights: Boolean,
    val rideModes: Boolean,
    val maxSpeed: Boolean,
    val tiltback: Boolean,
    val pedalSensitivity: Boolean,
    val pedalHorizontal: Boolean,
    val calibration: Boolean,
    val powerOff: Boolean,
    val volume: Boolean,
    val playSound: Boolean,
    val pinUnlock: Boolean,
    /**
     * `true` iff the device emits asynchronous alerts (I1 CAN-ID
     * `0x0F780101`, N2 alert PARAM, etc.). When `false`, consumers
     * should still subscribe to
     * [com.rideflux.domain.connection.WheelConnection.alerts] but
     * must not rely on it for timely safety notifications.
     */
    val asyncAlerts: Boolean,
)
