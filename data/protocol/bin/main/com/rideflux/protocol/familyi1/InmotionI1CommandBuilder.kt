/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi1

/**
 * Builders for Family I1 wire-level commands (`PROTOCOL_SPEC.md`
 * §10.4.1 – §10.4.7 and §9.5.1 PIN bootstrap).
 *
 * All frames are 16-byte CAN-envelope standard frames
 * (LEN = `0x08`, CHAN = `0x05`, FMT = `0x00`). `TYPE` defaults to
 * `0x00` (data frame); remote-frame queries (TYPE = `0x01`) are built
 * via [remoteQuery].
 *
 * Every call returns a fully escaped, checksummed wire-ready frame
 * starting with `AA AA` and ending with `55 55`, as defined in §2.6.
 */
object InmotionI1CommandBuilder {

    // ---- §3.5.1 CAN-ID registry -----------------------------------------

    const val CAN_ID_LIVE_TELEMETRY: Long = 0x0F550113L
    const val CAN_ID_STATIC_INFO: Long = 0x0F550114L
    const val CAN_ID_RIDE_MODE: Long = 0x0F550115L
    const val CAN_ID_REMOTE_CONTROL: Long = 0x0F550116L
    const val CAN_ID_CALIBRATION: Long = 0x0F550119L
    const val CAN_ID_HEADLIGHT: Long = 0x0F55010DL
    const val CAN_ID_HANDLE_BUTTON: Long = 0x0F55012EL
    const val CAN_ID_PIN: Long = 0x0F550307L
    const val CAN_ID_VOLUME: Long = 0x0F55060AL
    const val CAN_ID_PLAY_SOUND: Long = 0x0F550609L

    // ---- Framing constants ---------------------------------------------

    const val CHAN_HOST_TRAFFIC: Int = 0x05
    const val FMT_STANDARD: Int = 0x00
    const val FMT_EXTENDED: Int = 0x01
    const val TYPE_DATA: Int = 0x00
    const val TYPE_REMOTE: Int = 0x01

    /**
     * Build a standard 16-byte CAN-envelope frame for [canId] and
     * [data8] (exactly 8 bytes). The envelope is checksummed,
     * byte-stuffed, and wrapped in `AA AA … 55 55`.
     */
    fun buildStandard(
        canId: Long,
        data8: ByteArray,
        type: Int = TYPE_DATA,
    ): ByteArray {
        require(data8.size == 8) { "data8 must be 8 bytes, got ${data8.size}" }
        require(type in 0..0xFF) { "type out of range: $type" }
        val body = ByteArray(16)
        // CAN-ID, little-endian.
        body[0] = (canId and 0xFF).toByte()
        body[1] = ((canId ushr 8) and 0xFF).toByte()
        body[2] = ((canId ushr 16) and 0xFF).toByte()
        body[3] = ((canId ushr 24) and 0xFF).toByte()
        System.arraycopy(data8, 0, body, 4, 8)
        body[12] = 0x08
        body[13] = CHAN_HOST_TRAFFIC.toByte()
        body[14] = FMT_STANDARD.toByte()
        body[15] = type.toByte()
        return wrap(body)
    }

    /**
     * Remote-frame query (TYPE = `0x01`) on [canId] used to solicit
     * extended records such as `0x0F550113` (§9.5.1). The standard
     * DATA-8 solicitation is `FF FF FF FF FF FF FF FF`.
     */
    fun remoteQuery(canId: Long): ByteArray {
        val data8 = ByteArray(8) { 0xFF.toByte() }
        return buildStandard(canId, data8, type = TYPE_REMOTE)
    }

    /** Solicit the live-telemetry extended record (CAN-ID `0x0F550113`). */
    fun requestLiveTelemetry(): ByteArray = remoteQuery(CAN_ID_LIVE_TELEMETRY)

    /** Solicit the static / slow record (CAN-ID `0x0F550114`). */
    fun requestStaticInfo(): ByteArray = remoteQuery(CAN_ID_STATIC_INFO)

    // ---- §10.4.1 ride-mode cluster (`0x0F550115`) ------------------------

    /**
     * Set maximum speed (§10.4.1 sub `0x01`). The encoded value is
     * `maxKmh × 1000`, transmitted **big-endian** inside the DATA-8
     * payload at offsets 3..4.
     */
    fun setMaxSpeed(maxKmh: Double): ByteArray {
        val raw = (maxKmh * 1000.0).toInt().coerceIn(0, 0xFFFF)
        val data8 = ByteArray(8)
        data8[0] = 0x01
        data8[3] = ((raw ushr 8) and 0xFF).toByte()
        data8[4] = (raw and 0xFF).toByte()
        return buildStandard(CAN_ID_RIDE_MODE, data8)
    }

    /**
     * Set pedal sensitivity (§10.4.1 sub `0x06`). Raw wire value is
     * `(sens + 28) << 5`, big-endian at DATA-8[3..4]. Valid range is
     * implementation-defined; the spec does not bound [sens].
     */
    fun setPedalSensitivity(sens: Int): ByteArray {
        val raw = ((sens + 28) shl 5) and 0xFFFF
        val data8 = ByteArray(8)
        data8[0] = 0x06
        data8[3] = ((raw ushr 8) and 0xFF).toByte()
        data8[4] = (raw and 0xFF).toByte()
        return buildStandard(CAN_ID_RIDE_MODE, data8)
    }

    /**
     * Set ride-mode Classic (`true`) or Comfort (`false`), §10.4.1
     * sub `0x0A`.
     */
    fun setRideModeClassic(classic: Boolean): ByteArray {
        val data8 = ByteArray(8)
        data8[0] = 0x0A
        data8[3] = if (classic) 1.toByte() else 0.toByte()
        return buildStandard(CAN_ID_RIDE_MODE, data8)
    }

    /**
     * Set pedal horizontal-tilt angle (§10.4.1 sub `0x00`). Encoded
     * as `angle × 6553.6`, transmitted **big-endian** U32 in
     * DATA-8[3..6] (`b3` at offset 3).
     */
    fun setHorizontalTilt(angleDegrees: Double): ByteArray {
        val raw = (angleDegrees * 6553.6).toInt()
        val data8 = ByteArray(8)
        data8[0] = 0x00
        data8[3] = ((raw ushr 24) and 0xFF).toByte()
        data8[4] = ((raw ushr 16) and 0xFF).toByte()
        data8[5] = ((raw ushr 8) and 0xFF).toByte()
        data8[6] = (raw and 0xFF).toByte()
        return buildStandard(CAN_ID_RIDE_MODE, data8)
    }

    // ---- §10.4.2 remote-control cluster (`0x0F550116`) -------------------

    private fun remoteAction(action: Int): ByteArray {
        val data8 = ByteArray(8)
        data8[0] = 0xB2.toByte()
        data8[4] = action.toByte()
        return buildStandard(CAN_ID_REMOTE_CONTROL, data8)
    }

    /** Power off the wheel (§10.4.2 action `0x05`). */
    fun powerOff(): ByteArray = remoteAction(0x05)

    /** Turn LED strip on (§10.4.2 action `0x0F`). */
    fun ledStripOn(): ByteArray = remoteAction(0x0F)

    /** Turn LED strip off (§10.4.2 action `0x10`). */
    fun ledStripOff(): ByteArray = remoteAction(0x10)

    /** Emit a single beep (§10.4.2 action `0x11`). */
    fun beep(): ByteArray = remoteAction(0x11)

    // ---- §10.4.3 calibration (`0x0F550119`) -----------------------------

    /**
     * Enter wheel calibration mode. The DATA-8 must be
     * exactly `32 54 76 98 00 00 00 00` (§10.4.3).
     */
    fun calibration(): ByteArray {
        val data8 = byteArrayOf(0x32, 0x54, 0x76.toByte(), 0x98.toByte(), 0x00, 0x00, 0x00, 0x00)
        return buildStandard(CAN_ID_CALIBRATION, data8)
    }

    // ---- §10.4.4 headlight (`0x0F55010D`) -------------------------------

    /** Headlight on (`0x01`) / off (`0x00`) per §10.4.4. */
    fun setHeadlight(on: Boolean): ByteArray {
        val data8 = ByteArray(8)
        data8[0] = if (on) 1.toByte() else 0.toByte()
        return buildStandard(CAN_ID_HEADLIGHT, data8)
    }

    // ---- §10.4.5 handle-button (`0x0F55012E`) ---------------------------

    /**
     * Enable (`true`) or disable (`false`) handle-button detection,
     * §10.4.5. Note the inverted wire convention:
     * `enabled == true` → wire byte `0x00`.
     */
    fun setHandleButton(enabled: Boolean): ByteArray {
        val data8 = ByteArray(8)
        data8[0] = if (enabled) 0.toByte() else 1.toByte()
        return buildStandard(CAN_ID_HANDLE_BUTTON, data8)
    }

    // ---- §10.4.6 speaker volume (`0x0F55060A`) --------------------------

    /**
     * Set speaker volume in `0..100` (§10.4.6). Wire encoding is
     * `(volume × 100)` as U16LE at DATA-8[0..1].
     */
    fun setVolume(volume: Int): ByteArray {
        require(volume in 0..100) { "volume out of range: $volume" }
        val raw = volume * 100
        val data8 = ByteArray(8)
        data8[0] = (raw and 0xFF).toByte()
        data8[1] = ((raw ushr 8) and 0xFF).toByte()
        return buildStandard(CAN_ID_VOLUME, data8)
    }

    // ---- §10.4.7 play sound (`0x0F550609`) ------------------------------

    /** Play built-in sound at index `0..255` (§10.4.7). */
    fun playSound(index: Int): ByteArray {
        require(index in 0..0xFF) { "sound index out of range: $index" }
        val data8 = ByteArray(8)
        data8[0] = index.toByte()
        return buildStandard(CAN_ID_PLAY_SOUND, data8)
    }

    // ---- §9.5.1 PIN bootstrap -------------------------------------------

    /**
     * Send the 6-digit ASCII PIN for bootstrap (§9.5.1). [pin] must
     * be exactly 6 ASCII digits; the default wheel PIN is `"000000"`.
     */
    fun sendPin(pin: String = "000000"): ByteArray {
        require(pin.length == 6) { "PIN must be 6 characters, got ${pin.length}" }
        require(pin.all { it in '0'..'9' }) { "PIN must be ASCII digits" }
        val data8 = ByteArray(8)
        for (i in 0..5) data8[i] = pin[i].code.toByte()
        // bytes 6..7 remain 0x00 per §9.5.1.
        return buildStandard(CAN_ID_PIN, data8)
    }

    // ---- Internal: escape + checksum + wrap -----------------------------

    private fun wrap(body: ByteArray): ByteArray {
        val check = InmotionI1Codec.checksum(body)
        val escaped = InmotionI1Codec.escape(body)
        val out = ByteArray(2 + escaped.size + 1 + 2)
        out[0] = InmotionI1Codec.PREAMBLE_BYTE
        out[1] = InmotionI1Codec.PREAMBLE_BYTE
        System.arraycopy(escaped, 0, out, 2, escaped.size)
        out[2 + escaped.size] = check
        out[2 + escaped.size + 1] = InmotionI1Codec.TRAILER_BYTE
        out[2 + escaped.size + 2] = InmotionI1Codec.TRAILER_BYTE
        return out
    }
}
