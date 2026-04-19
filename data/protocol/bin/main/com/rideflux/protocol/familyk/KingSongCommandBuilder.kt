/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of RideFlux. It is licensed under the GNU General
 * Public License, version 3 or (at your option) any later version.
 * See the LICENSE file in the repository root for the full text.
 */
package com.rideflux.protocol.familyk

/**
 * Command builder for Family K (KingSong) host-to-device frames.
 *
 * Every command is a fixed 20-byte frame (§2.2):
 * ```
 *   [0]  AA                     header high
 *   [1]  55                     header low
 *   [2..15]  payload            per-command, little-endian ints,
 *                               zero-filled if unused
 *   [16] CMD                    command code (§3.2)
 *   [17] trailer-magic          command-specific constant;
 *                               default 0x14 per §2.2
 *   [18] 5A                     tail
 *   [19] 5A                     tail
 * ```
 *
 * The builder exposes a generic [command] factory plus named helpers
 * for the commands enumerated in §3.2 whose semantics are fully
 * specified by the spec.
 */
object KingSongCommandBuilder {

    const val FRAME_SIZE: Int = 20

    private const val HEADER_HIGH: Byte = 0xAA.toByte()
    private const val HEADER_LOW: Byte = 0x55
    private const val TAIL_BYTE: Byte = 0x5A

    /** Default value for byte 17 of a host-to-device frame per §2.2. */
    const val DEFAULT_TRAILER_MAGIC: Byte = 0x14

    // Command codes from §3.2.
    const val CMD_REQUEST_SERIAL_NUMBER: Int = 0x63
    const val CMD_REQUEST_DEVICE_NAME: Int = 0x9B
    const val CMD_REQUEST_ALARM_SETTINGS: Int = 0x98
    const val CMD_SET_ALARM_AND_MAX_SPEED: Int = 0x85
    const val CMD_SET_PEDALS_MODE: Int = 0x87
    const val CMD_BEEP: Int = 0x88
    const val CMD_WHEEL_CALIBRATION: Int = 0x89
    const val CMD_LIGHT_MODE: Int = 0x73
    const val CMD_LED_MODE: Int = 0x6C
    const val CMD_STROBE_MODE: Int = 0x53
    const val CMD_POWER_OFF: Int = 0x40
    const val CMD_STANDBY_DELAY: Int = 0x3F

    // Per-command trailer-magic constants from §3.2.
    private const val TRAILER_SET_PEDALS: Byte = 0x15

    /**
     * Generic frame factory. Call sites that need a command not
     * covered by a named helper can use this directly.
     *
     * @param commandCode  value for byte 16 (low 8 bits used)
     * @param payload      exactly 14 bytes placed at offsets 2..15
     * @param trailerMagic value for byte 17; defaults to 0x14 (§2.2)
     */
    fun command(
        commandCode: Int,
        payload: ByteArray = ByteArray(14),
        trailerMagic: Byte = DEFAULT_TRAILER_MAGIC,
    ): ByteArray {
        require(payload.size == 14) {
            "Family K payload must be exactly 14 bytes, got ${payload.size}"
        }
        val out = ByteArray(FRAME_SIZE)
        out[0] = HEADER_HIGH
        out[1] = HEADER_LOW
        System.arraycopy(payload, 0, out, 2, 14)
        out[16] = (commandCode and 0xFF).toByte()
        out[17] = trailerMagic
        out[18] = TAIL_BYTE
        out[19] = TAIL_BYTE
        return out
    }

    // --- §9.2 information requests -----------------------------------

    /** Request the device serial number (reply: `0xB3`). */
    fun requestSerialNumber(): ByteArray = command(CMD_REQUEST_SERIAL_NUMBER)

    /** Request the device / marketing name (reply: `0xBB`). */
    fun requestDeviceName(): ByteArray = command(CMD_REQUEST_DEVICE_NAME)

    /** Request alarm settings and max speed (reply: `0xA4`/`0xB5`). */
    fun requestAlarmSettings(): ByteArray = command(CMD_REQUEST_ALARM_SETTINGS)

    // --- §3.2 actuator commands --------------------------------------

    /** Beep command. */
    fun beep(): ByteArray = command(CMD_BEEP)

    /** Wheel-calibration command. */
    fun wheelCalibration(): ByteArray = command(CMD_WHEEL_CALIBRATION)

    /** Power the wheel off immediately. */
    fun powerOff(): ByteArray = command(CMD_POWER_OFF)

    /**
     * Set pedals-mode preset (`0x87`, §10.3.1).
     *
     * Wire layout:
     *  * byte 2 = `pedalsMode` (`0` hard / `1` medium / `2` soft);
     *  * byte 3 = `0xE0` (required magic constant);
     *  * byte 16 = `0x87`;
     *  * byte 17 = `0x15` (overrides the default tail).
     *
     * An enum-typed overload delegates here; this raw form is kept
     * because §10.3.1 notes that the mode value is not range-limited
     * by the protocol itself.
     */
    fun setPedalsMode(modeIndex: Int): ByteArray {
        require(modeIndex in 0..255) { "modeIndex out of range" }
        val payload = ByteArray(14)
        payload[0] = modeIndex.toByte()
        payload[1] = 0xE0.toByte()
        return command(
            commandCode = CMD_SET_PEDALS_MODE,
            payload = payload,
            trailerMagic = TRAILER_SET_PEDALS,
        )
    }

    /** Typed overload for [setPedalsMode] mapping `HARD=0`, `MEDIUM=1`, `SOFT=2`. */
    fun setPedalsMode(mode: PedalsMode): ByteArray = setPedalsMode(
        when (mode) {
            PedalsMode.HARD -> 0
            PedalsMode.MEDIUM -> 1
            PedalsMode.SOFT -> 2
        },
    )

    /**
     * Set the three alarm speeds and the maximum (tiltback) speed in
     * a single frame (`0x85`, §10.3.2).
     *
     * Each value is an unsigned 8-bit integer expressed in km/h.
     * Byte placement: `alarm1 @2`, `alarm2 @4`, `alarm3 @6`,
     * `maxSpeed @8`; bytes 3/5/7 are zero padding.
     *
     * **Degenerate case** per §10.3.2: if all four values are zero,
     * the frame is rewritten as a `0x98` query (same payload, only
     * byte 16 differs) so the device reports its current settings
     * rather than silently writing zeros.
     */
    fun setAlarmAndMaxSpeed(
        alarm1Kmh: Int,
        alarm2Kmh: Int,
        alarm3Kmh: Int,
        maxSpeedKmh: Int,
    ): ByteArray {
        require(alarm1Kmh in 0..255) { "alarm1Kmh out of range" }
        require(alarm2Kmh in 0..255) { "alarm2Kmh out of range" }
        require(alarm3Kmh in 0..255) { "alarm3Kmh out of range" }
        require(maxSpeedKmh in 0..255) { "maxSpeedKmh out of range" }

        val code = if (alarm1Kmh == 0 && alarm2Kmh == 0 && alarm3Kmh == 0 && maxSpeedKmh == 0) {
            CMD_REQUEST_ALARM_SETTINGS
        } else {
            CMD_SET_ALARM_AND_MAX_SPEED
        }
        val payload = ByteArray(14)
        payload[0] = alarm1Kmh.toByte() // frame offset 2
        payload[2] = alarm2Kmh.toByte() // frame offset 4
        payload[4] = alarm3Kmh.toByte() // frame offset 6
        payload[6] = maxSpeedKmh.toByte() // frame offset 8
        return command(commandCode = code, payload = payload)
    }

    /**
     * Set headlight mode (`0x73`, §10.3.3).
     *
     * Byte 2 carries `0x12 + lightMode` (NOT the raw mode value);
     * byte 3 is a required `0x01` magic constant. Valid modes are
     * firmware-defined but canonical values are `0` (off), `1` (on),
     * `2` (auxiliary / special).
     */
    fun setLightMode(lightMode: Int): ByteArray {
        require(lightMode in 0..255 - 0x12) { "lightMode out of range (would overflow byte 2)" }
        val payload = ByteArray(14)
        payload[0] = (0x12 + lightMode).toByte()
        payload[1] = 0x01
        return command(CMD_LIGHT_MODE, payload)
    }

    /** Typed overload for [setLightMode] mapping `OFF=0`, `ON=1`, `AUX=2`. */
    fun setLightMode(mode: LightMode): ByteArray = setLightMode(
        when (mode) {
            LightMode.OFF -> 0
            LightMode.ON -> 1
            LightMode.AUX -> 2
        },
    )

    /**
     * Set LED pattern mode (`0x6C`, §10.3.4).
     *
     * Byte 2 carries the mode value directly; no bias, no magic
     * constants. Valid range is wheel-dependent, so the helper
     * accepts any 8-bit unsigned value.
     */
    fun setLedMode(ledMode: Int): ByteArray {
        require(ledMode in 0..255) { "ledMode out of range" }
        val payload = ByteArray(14)
        payload[0] = ledMode.toByte()
        return command(CMD_LED_MODE, payload)
    }

    /**
     * Set strobe pattern mode (`0x53`, §10.3.5).
     *
     * Byte 2 carries the mode value directly. Valid range is
     * wheel-dependent.
     */
    fun setStrobeMode(strobeMode: Int): ByteArray {
        require(strobeMode in 0..255) { "strobeMode out of range" }
        val payload = ByteArray(14)
        payload[0] = strobeMode.toByte()
        return command(CMD_STROBE_MODE, payload)
    }

    enum class PedalsMode { HARD, MEDIUM, SOFT }
    enum class LightMode { OFF, ON, AUX }
}
