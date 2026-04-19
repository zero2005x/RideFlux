/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of RideFlux. It is licensed under the GNU General
 * Public License, version 3 or (at your option) any later version.
 * See the LICENSE file in the repository root for the full text.
 */
package com.rideflux.protocol.familyg

/**
 * Command builder for Family G (Begode / Gotway / ExtremeBull).
 *
 * The spec describes Family G host commands as short ASCII byte
 * sequences written with GATT write-without-response (§2.1 + §10.1).
 * The mapping is almost entirely one-byte-per-command; a handful of
 * commands are two-byte sequences sent ~100–300 ms apart.
 *
 * This builder produces the **byte representation** only. Command
 * **timing** (e.g. the 300 ms gap between the two bytes of the
 * calibration command per §10.1) is the responsibility of a
 * higher-level transport layer.
 *
 * All commands returned here are ASCII-encoded per §9.1 / §10.1 and
 * contain no null terminators.
 */
object BegodeCommandBuilder {

    // --- §9.1 identification commands ---------------------------------

    /** Request device name ("NAME ...") per §9.1. */
    fun requestName(): ByteArray = byteArrayOf('N'.code.toByte())

    /** Request firmware identification strings per §9.1. */
    fun requestFirmware(): ByteArray = byteArrayOf('V'.code.toByte())

    // --- §10.1 control commands (single-byte) -------------------------

    /** Beep command (`b`, §10.1). */
    fun beep(): ByteArray = byteArrayOf('b'.code.toByte())

    /** Switch distance units to miles (`m`). */
    fun unitsMiles(): ByteArray = byteArrayOf('m'.code.toByte())

    /** Switch distance units to kilometres (`g`). */
    fun unitsKilometres(): ByteArray = byteArrayOf('g'.code.toByte())

    /** Headlight control (§10.1). */
    fun lightMode(mode: LightMode): ByteArray = byteArrayOf(
        when (mode) {
            LightMode.OFF -> 'E'
            LightMode.ON -> 'Q'
            LightMode.STROBE -> 'T'
        }.code.toByte(),
    )

    /** Roll-angle / ride-sensitivity preset (§10.1). */
    fun rollAngle(mode: RollAngle): ByteArray = byteArrayOf(
        when (mode) {
            RollAngle.SOFT -> '<'
            RollAngle.MEDIUM -> '='
            RollAngle.HARD -> '>'
        }.code.toByte(),
    )

    /**
     * Pedals stiffness (§10.1 final row).
     *
     * Note: the spec's single-character mnemonics for pedals mode
     * overlap with other commands (`h` is also used elsewhere), so
     * the CUSTOM_FIRMWARE value is reserved for the custom-firmware
     * opcode form which is the `i` byte per §10.1.
     */
    fun pedalsMode(mode: PedalsMode): ByteArray = byteArrayOf(
        when (mode) {
            PedalsMode.HARD -> 'h'
            PedalsMode.MEDIUM -> 'f'
            PedalsMode.SOFT -> 's'
        }.code.toByte(),
    )

    /**
     * Speed-alarm mode (§10.1 row 6).
     *
     * `LEVEL_2` triggers at the lower speed, `LEVEL_1` at the higher;
     * `OFF` disables audible alarms.
     */
    fun alarmMode(mode: AlarmMode): ByteArray = byteArrayOf(
        when (mode) {
            AlarmMode.LEVEL_2 -> 'o'
            AlarmMode.LEVEL_1 -> 'u'
            AlarmMode.OFF -> 'i'
        }.code.toByte(),
    )

    // --- §10.1 multi-byte sequences -----------------------------------

    /**
     * Wheel calibration command (`c` followed ~300 ms later by `y`),
     * serialised as a single two-byte array for the caller. The caller
     * MUST space the writes correctly on the transport — the spec
     * requires roughly 300 ms between the two bytes.
     */
    fun wheelCalibration(): ByteArray = byteArrayOf(
        'c'.code.toByte(),
        'y'.code.toByte(),
    )

    // --- enums --------------------------------------------------------

    enum class LightMode { OFF, ON, STROBE }
    enum class RollAngle { SOFT, MEDIUM, HARD }
    enum class PedalsMode { HARD, MEDIUM, SOFT }
    enum class AlarmMode { LEVEL_2, LEVEL_1, OFF }
}
