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

    /** ASCII-encode a single command byte. */
    private fun cmd(c: Char): ByteArray = byteArrayOf(c.code.toByte())

    // --- §9.1 identification commands ---------------------------------

    /** Request device name ("NAME ...") per §9.1. */
    fun requestName(): ByteArray = cmd('N')

    /** Request firmware identification strings per §9.1. */
    fun requestFirmware(): ByteArray = cmd('V')

    // --- §10.1 control commands (single-byte) -------------------------

    /** Beep command (`b`, §10.1). */
    fun beep(): ByteArray = cmd('b')

    /** Switch distance units to miles (`m`). */
    fun unitsMiles(): ByteArray = cmd('m')

    /** Switch distance units to kilometres (`g`). */
    fun unitsKilometres(): ByteArray = cmd('g')

    /** Headlight control (§10.1). */
    fun lightMode(mode: LightMode): ByteArray = cmd(
        when (mode) {
            LightMode.OFF -> 'E'
            LightMode.ON -> 'Q'
            LightMode.STROBE -> 'T'
        },
    )

    /** Roll-angle / ride-sensitivity preset (§10.1). */
    fun rollAngle(mode: RollAngle): ByteArray = cmd(
        when (mode) {
            RollAngle.SOFT -> '<'
            RollAngle.MEDIUM -> '='
            RollAngle.HARD -> '>'
        },
    )

    /**
     * Pedals stiffness (§10.1 final row).
     *
     * Note: the spec's single-character mnemonics for pedals mode
     * overlap with other commands (`h` is also used elsewhere), so
     * the CUSTOM_FIRMWARE value is reserved for the custom-firmware
     * opcode form which is the `i` byte per §10.1.
     */
    fun pedalsMode(mode: PedalsMode): ByteArray = cmd(
        when (mode) {
            PedalsMode.HARD -> 'h'
            PedalsMode.MEDIUM -> 'f'
            PedalsMode.SOFT -> 's'
        },
    )

    /**
     * Speed-alarm mode (§10.1 row 6).
     *
     * `LEVEL_2` triggers at the lower speed, `LEVEL_1` at the higher;
     * `OFF` disables audible alarms.
     */
    fun alarmMode(mode: AlarmMode): ByteArray = cmd(
        when (mode) {
            AlarmMode.LEVEL_2 -> 'o'
            AlarmMode.LEVEL_1 -> 'u'
            AlarmMode.OFF -> 'i'
        },
    )

    // --- §10.1 multi-byte sequences -----------------------------------

    /**
     * Wheel calibration command per §10.1: `c` followed ~300 ms later
     * by `y`. The two bytes must go out as two GATT write-without-
     * response calls spaced by [TwoStepCommand.delayMs], NOT merged
     * into a single write. The transport MUST honour the timing.
     */
    fun wheelCalibration(): TwoStepCommand =
        TwoStepCommand(cmd('c'), delayMs = 300L, cmd('y'))

    /**
     * A command that must be delivered as two writes spaced by
     * [delayMs]. Encodes the timing constraint so a transport layer
     * cannot accidentally collapse the two bytes into one write.
     */
    data class TwoStepCommand(
        val first: ByteArray,
        val delayMs: Long,
        val second: ByteArray,
    )

    // --- enums --------------------------------------------------------

    enum class LightMode { OFF, ON, STROBE }
    enum class RollAngle { SOFT, MEDIUM, HARD }
    enum class PedalsMode { HARD, MEDIUM, SOFT }
    enum class AlarmMode { LEVEL_2, LEVEL_1, OFF }
}
