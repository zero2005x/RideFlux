/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi2

/**
 * Builders for Family I2 wire-level commands (`PROTOCOL_SPEC.md`
 * §9.5.2 identification, §10.4.8 power-off, §10.4.9 settings writes).
 *
 * Frames are emitted wire-ready: preamble `AA AA`, the escape-stuffed
 * body `FLAGS | LEN | CMD | DATA[LEN-1]`, and the raw XOR checksum.
 * I2 has no trailer (§2.7.1).
 */
object InmotionI2CommandBuilder {

    // ---- CMD registry (§3.5.4) ------------------------------------------

    const val CMD_MAIN_INFO: Int = 0x02
    const val CMD_DIAGNOSTIC: Int = 0x03
    const val CMD_REALTIME: Int = 0x04
    const val CMD_BATTERY: Int = 0x05
    const val CMD_PING: Int = 0x10
    const val CMD_TOTAL_STATS: Int = 0x11
    const val CMD_SETTINGS: Int = 0x20
    const val CMD_CONTROL: Int = 0x60

    // ---- MainInfo sub-selectors (§9.5.2) --------------------------------

    const val MAIN_INFO_CAR_TYPE: Int = 0x01
    const val MAIN_INFO_SERIAL: Int = 0x02
    const val MAIN_INFO_VERSION: Int = 0x06

    /**
     * Build a wire-ready I2 frame with explicit [flags], [cmd], and
     * [data]. [flags] must be one of `0x11` or `0x14` (§2.7.2). [cmd]
     * is masked into `0..0x7F` on emission — host-initiated frames
     * never set the reserved high bit (§2.7.1).
     */
    fun build(flags: Int, cmd: Int, data: ByteArray): ByteArray {
        require(flags == InmotionI2Codec.FLAGS_INIT || flags == InmotionI2Codec.FLAGS_DEFAULT) {
            "flags must be 0x11 or 0x14 per §2.7.2, got ${"%#04x".format(flags)}"
        }
        require(cmd in 0..0xFF) { "cmd out of range: $cmd" }
        require(data.size <= 0xFE) { "DATA too large: ${data.size}" }

        val len = data.size + 1
        val body = ByteArray(3 + data.size)
        body[0] = flags.toByte()
        body[1] = len.toByte()
        body[2] = (cmd and 0x7F).toByte()
        System.arraycopy(data, 0, body, 3, data.size)

        val check = InmotionI2Codec.xorChecksum(body)
        val escaped = InmotionI2Codec.escape(body)
        val out = ByteArray(2 + escaped.size + 1)
        out[0] = InmotionI2Codec.PREAMBLE_BYTE
        out[1] = InmotionI2Codec.PREAMBLE_BYTE
        System.arraycopy(escaped, 0, out, 2, escaped.size)
        out[2 + escaped.size] = check
        return out
    }

    // ---- §9.5.2 identification ------------------------------------------

    /** Request the car-type record (FLAGS `0x11`, CMD `0x02`, DATA `0x01`). */
    fun requestCarType(): ByteArray =
        build(InmotionI2Codec.FLAGS_INIT, CMD_MAIN_INFO, byteArrayOf(MAIN_INFO_CAR_TYPE.toByte()))

    /** Request the serial-number record (FLAGS `0x11`, CMD `0x02`, DATA `0x02`). */
    fun requestSerial(): ByteArray =
        build(InmotionI2Codec.FLAGS_INIT, CMD_MAIN_INFO, byteArrayOf(MAIN_INFO_SERIAL.toByte()))

    /** Request the version record (FLAGS `0x11`, CMD `0x02`, DATA `0x06`). */
    fun requestVersion(): ByteArray =
        build(InmotionI2Codec.FLAGS_INIT, CMD_MAIN_INFO, byteArrayOf(MAIN_INFO_VERSION.toByte()))

    // ---- Informational requests (§3.5.4) --------------------------------

    fun requestRealtime(): ByteArray = build(InmotionI2Codec.FLAGS_DEFAULT, CMD_REALTIME, ByteArray(0))
    fun requestBattery(): ByteArray = build(InmotionI2Codec.FLAGS_DEFAULT, CMD_BATTERY, ByteArray(0))
    fun requestTotalStats(): ByteArray = build(InmotionI2Codec.FLAGS_DEFAULT, CMD_TOTAL_STATS, ByteArray(0))

    /** Settings request (§3.5.7); DATA is the echo sub-selector `0x20`. */
    fun requestSettings(): ByteArray =
        build(InmotionI2Codec.FLAGS_DEFAULT, CMD_SETTINGS, byteArrayOf(0x20))

    /** Reserved ping keep-alive tick (§9.5.2 step 3). DATA is `00 01`. */
    fun ping(): ByteArray =
        build(InmotionI2Codec.FLAGS_DEFAULT, CMD_PING, byteArrayOf(0x00, 0x01))

    // ---- §10.4.8 power-off ---------------------------------------------

    /** Stage-1 power-off request (§10.4.8): FLAGS `0x11`, CMD `0x03`, DATA `81 00`. */
    fun powerOffStage1(): ByteArray =
        build(InmotionI2Codec.FLAGS_INIT, CMD_DIAGNOSTIC, byteArrayOf(0x81.toByte(), 0x00))

    /** Stage-2 power-off confirmation (§10.4.8). MUST follow the wheel's reply. */
    fun powerOffStage2(): ByteArray =
        build(InmotionI2Codec.FLAGS_INIT, CMD_DIAGNOSTIC, byteArrayOf(0x82.toByte()))

    // ---- §10.4.9 setting writes (CMD 0x60) ------------------------------

    /** Generic Control frame with [subCmd] and [payload], FLAGS = `0x14`. */
    fun control(subCmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(subCmd in 0..0xFF) { "subCmd out of range: $subCmd" }
        val data = ByteArray(1 + payload.size)
        data[0] = subCmd.toByte()
        System.arraycopy(payload, 0, data, 1, payload.size)
        return build(InmotionI2Codec.FLAGS_DEFAULT, CMD_CONTROL, data)
    }

    private fun controlU8(subCmd: Int, v: Int): ByteArray =
        control(subCmd, byteArrayOf((v and 0xFF).toByte()))

    private fun controlU16BE(subCmd: Int, v: Int): ByteArray =
        control(
            subCmd,
            byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()),
        )

    /**
     * §10.4.9 sub `0x21` — max speed (universal single-value form):
     * U16BE of `maxKmh × 100`.
     */
    fun setMaxSpeed(maxKmh: Double): ByteArray =
        controlU16BE(0x21, (maxKmh * 100.0).toInt().coerceIn(0, 0xFFFF))

    /**
     * §10.4.9 sub `0x21` — max-speed + alarm-1 combined (V13 / V14 /
     * V11Y / V9 / V12S form): two U16BE values back-to-back.
     */
    fun setMaxSpeedWithAlarm1(maxKmh: Double, alarmKmh: Double): ByteArray {
        val maxRaw = (maxKmh * 100.0).toInt().coerceIn(0, 0xFFFF)
        val alarmRaw = (alarmKmh * 100.0).toInt().coerceIn(0, 0xFFFF)
        return control(
            0x21,
            byteArrayOf(
                ((maxRaw ushr 8) and 0xFF).toByte(), (maxRaw and 0xFF).toByte(),
                ((alarmRaw ushr 8) and 0xFF).toByte(), (alarmRaw and 0xFF).toByte(),
            ),
        )
    }

    /** §10.4.9 sub `0x22` — pedal horizon tilt: U16BE of `angle × 10`. */
    fun setPedalHorizonTilt(angleDegrees: Double): ByteArray =
        controlU16BE(0x22, (angleDegrees * 10.0).toInt())

    /** §10.4.9 sub `0x23` — Classic (1) / Comfort (0) mode. */
    fun setRideModeClassic(classic: Boolean): ByteArray = controlU8(0x23, if (classic) 1 else 0)

    /** §10.4.9 sub `0x24` — Fancier-mode toggle. */
    fun setFancierMode(on: Boolean): ByteArray = controlU8(0x24, if (on) 1 else 0)

    /** §10.4.9 sub `0x25` — pedal sensitivity (two identical bytes). */
    fun setPedalSensitivity(sens: Int): ByteArray {
        require(sens in 0..0xFF) { "sens out of range: $sens" }
        return control(0x25, byteArrayOf(sens.toByte(), sens.toByte()))
    }

    /** §10.4.9 sub `0x26` — speaker volume (0..100). */
    fun setVolume(volume: Int): ByteArray {
        require(volume in 0..100) { "volume out of range: $volume" }
        return controlU8(0x26, volume)
    }

    /** §10.4.9 sub `0x28` — stand-by delay: U16BE of `delayMin × 60`. */
    fun setStandbyDelay(delayMinutes: Int): ByteArray =
        controlU16BE(0x28, (delayMinutes * 60).coerceIn(0, 0xFFFF))

    /**
     * §10.4.9 sub `0x2B` — light brightness (single-value form used
     * by V11 / V13 / V14 / V11Y / V9 / V12S).
     */
    fun setLightBrightness(value: Int): ByteArray {
        require(value in 0..255) { "value out of range: $value" }
        return controlU8(0x2B, value)
    }

    /** §10.4.9 sub `0x2B` — low+high beam (V12-HS/HT/PRO form). */
    fun setBeamBrightness(low: Int, high: Int): ByteArray {
        require(low in 0..255 && high in 0..255) { "brightness out of range" }
        return control(0x2B, byteArrayOf(low.toByte(), high.toByte()))
    }

    /** §10.4.9 sub `0x2C` — mute. Inverted sense: `true` → wire `0`. */
    fun setMute(muted: Boolean): ByteArray = controlU8(0x2C, if (muted) 0 else 1)

    /** §10.4.9 sub `0x2D` — DRL. */
    fun setDaytimeRunningLight(on: Boolean): ByteArray = controlU8(0x2D, if (on) 1 else 0)

    /**
     * §10.4.9 sub `0x2E` — handle-button. Inverted sense:
     * `enabled == true` → wire `0`.
     */
    fun setHandleButton(enabled: Boolean): ByteArray = controlU8(0x2E, if (enabled) 0 else 1)

    /** §10.4.9 sub `0x2F` — auto-light. */
    fun setAutoLight(on: Boolean): ByteArray = controlU8(0x2F, if (on) 1 else 0)

    /** §10.4.9 sub `0x31` — lock mode. */
    fun setLockMode(on: Boolean): ByteArray = controlU8(0x31, if (on) 1 else 0)

    /** §10.4.9 sub `0x32` — transport mode. */
    fun setTransportMode(on: Boolean): ByteArray = controlU8(0x32, if (on) 1 else 0)

    /** §10.4.9 sub `0x37` — go-home mode. */
    fun setGoHomeMode(on: Boolean): ByteArray = controlU8(0x37, if (on) 1 else 0)

    /** §10.4.9 sub `0x38` — fan quiet mode. */
    fun setFanQuietMode(on: Boolean): ByteArray = controlU8(0x38, if (on) 1 else 0)

    /** §10.4.9 sub `0x39` — sound-wave effect. */
    fun setSoundWave(on: Boolean): ByteArray = controlU8(0x39, if (on) 1 else 0)

    /** §10.4.9 sub `0x3E` — alarm-1 / alarm-2 (V11 / V12 form). */
    fun setAlarmSpeeds(alarm1Kmh: Double, alarm2Kmh: Double): ByteArray {
        val a1 = (alarm1Kmh * 100.0).toInt().coerceIn(0, 0xFFFF)
        val a2 = (alarm2Kmh * 100.0).toInt().coerceIn(0, 0xFFFF)
        return control(
            0x3E,
            byteArrayOf(
                ((a1 ushr 8) and 0xFF).toByte(), (a1 and 0xFF).toByte(),
                ((a2 ushr 8) and 0xFF).toByte(), (a2 and 0xFF).toByte(),
            ),
        )
    }

    /** §10.4.9 sub `0x3E` — split-mode toggle (non-V12-H form). */
    fun setSplitModeToggle(on: Boolean): ByteArray = controlU8(0x3E, if (on) 1 else 0)

    /** §10.4.9 sub `0x3F` — split-mode accel + brake (V11/V13/V14/…). */
    fun setSplitModeAccelBrake(accel: Int, brake: Int): ByteArray {
        require(accel in 0..255 && brake in 0..255) { "range 0..255" }
        return control(0x3F, byteArrayOf(accel.toByte(), brake.toByte()))
    }

    /** §10.4.9 sub `0x40` — split-mode accel + brake (V12-HS/HT/PRO). */
    fun setSplitModeAccelBrakeV12H(accel: Int, brake: Int): ByteArray {
        require(accel in 0..255 && brake in 0..255) { "range 0..255" }
        return control(0x40, byteArrayOf(accel.toByte(), brake.toByte()))
    }

    /** §10.4.9 sub `0x40` — headlight (V11 `< 1.4` only). */
    fun setHeadlightLegacy(on: Boolean): ByteArray = controlU8(0x40, if (on) 1 else 0)

    /** §10.4.9 sub `0x41` — play sound (V11 `< 1.4` only). */
    fun playSoundLegacy(index: Int): ByteArray {
        require(index in 0..255) { "index out of range" }
        return control(0x41, byteArrayOf(index.toByte(), 0x01))
    }

    /** §10.4.9 sub `0x42` — wheel calibration (universal). */
    fun calibration(): ByteArray = control(0x42, byteArrayOf(0x01, 0x00, 0x01))

    /** §10.4.9 sub `0x42` — split-mode toggle (V12-HS/HT/PRO). */
    fun setSplitModeToggleV12H(on: Boolean): ByteArray = controlU8(0x42, if (on) 1 else 0)

    /** §10.4.9 sub `0x43` — cooling-fan override. */
    fun setCoolingFanOverride(on: Boolean): ByteArray = controlU8(0x43, if (on) 1 else 0)

    /** §10.4.9 sub `0x45` — berm-angle mode. */
    fun setBermAngleMode(on: Boolean): ByteArray = controlU8(0x45, if (on) 1 else 0)

    /** §10.4.9 sub `0x50` — headlight (V11 ≥ 1.4 and newer). */
    fun setHeadlight(on: Boolean): ByteArray = controlU8(0x50, if (on) 1 else 0)

    /** §10.4.9 sub `0x50` — low/high-beam (V12-HS/HT/PRO). */
    fun setBeams(lowBeamOn: Boolean, highBeamOn: Boolean): ByteArray =
        control(0x50, byteArrayOf(if (lowBeamOn) 1 else 0, if (highBeamOn) 1 else 0))

    /** §10.4.9 sub `0x51` — play sound (V11 ≥ 1.4 and newer). */
    fun playSound(index: Int): ByteArray {
        require(index in 0..255) { "index out of range" }
        return control(0x51, byteArrayOf(index.toByte(), 0x01))
    }

    /** §10.4.9 sub `0x51` — play beep (V13 / V13 PRO / V14 / V11Y). */
    fun playBeep(index: Int): ByteArray {
        require(index in 0..255) { "index out of range" }
        return control(0x51, byteArrayOf(index.toByte(), 0x64))
    }

    /** §10.4.9 sub `0x52` — wheel calibration (turn variant). */
    fun calibrationTurn(): ByteArray = control(0x52, byteArrayOf(0x01, 0x00, 0x01))

    /** §10.4.9 sub `0x52` — wheel calibration (balance variant). */
    fun calibrationBalance(): ByteArray = control(0x52, byteArrayOf(0x01, 0x01, 0x00))
}
