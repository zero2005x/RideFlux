/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyn

/**
 * Command builder for Families N1 / N2 (Ninebot).
 *
 * The builder takes frame fields — either as a pre-built
 * [NinebotFrame] or as discrete parameters — computes the §6.1
 * checksum, applies the §5.1 XOR keystream, and returns the full
 * on-wire byte sequence starting with the `55 AA` prefix.
 *
 * Endpoint address constants come from the default / S2 / Mini row
 * of the §3.4 endpoint table; the defaults are used here. Callers
 * that need a per-model override should pass the address directly.
 */
object NinebotCommandBuilder {

    // --- Endpoint addresses (default column of §3.4) -----------------

    const val ADDR_CONTROLLER: Int = 0x01
    const val ADDR_KEY_GENERATOR: Int = 0x16
    const val ADDR_HOST_APP_DEFAULT: Int = 0x09

    // Per-model Host-App overrides from §3.4.
    const val ADDR_HOST_APP_S2: Int = 0x11
    const val ADDR_HOST_APP_MINI: Int = 0x0A

    // --- N2 CMD codes (§3.4) -----------------------------------------

    const val CMD_READ: Int = 0x01
    const val CMD_WRITE: Int = 0x03
    const val CMD_GET: Int = 0x04
    const val CMD_GET_KEY: Int = 0x5B

    // --- Selected PARAM codes (§3.4) ---------------------------------

    const val PARAM_SERIAL_PRIMARY: Int = 0x10
    const val PARAM_SERIAL_CONTINUATION: Int = 0x13
    const val PARAM_SERIAL_ALTERNATE: Int = 0x16
    const val PARAM_FIRMWARE_VERSION: Int = 0x1A
    const val PARAM_BATTERY_LEVEL: Int = 0x22
    const val PARAM_ATTITUDE: Int = 0x61
    const val PARAM_ACTIVATION_DATE: Int = 0x69
    const val PARAM_GET_KEY: Int = 0x5B

    /** `55 AA` wire prefix. */
    private val PREFIX = byteArrayOf(0x55, 0xAA.toByte())

    /** Build a wire-ready byte sequence for [frame] using [gamma]. */
    fun build(
        frame: NinebotFrame,
        gamma: ByteArray = NinebotCodec.ZERO_KEYSTREAM,
    ): ByteArray {
        require(gamma.size == NinebotCodec.KEYSTREAM_SIZE) {
            "γ must be exactly ${NinebotCodec.KEYSTREAM_SIZE} bytes, got ${gamma.size}"
        }

        val cmdPresent = frame.family == NinebotFamily.N2
        val dataSize = frame.data.size
        // Per §2.4 / §2.5: LEN = DATA.size + 2 (N1) or + 3 (N2).
        val lenByte = dataSize + if (cmdPresent) 3 else 2
        require(lenByte in 0..255) { "LEN byte would overflow: $lenByte" }

        // Pre-checksum bytes: LEN, SRC, DST, (CMD,) PARAM, DATA...
        val preChkSize = if (cmdPresent) 5 + dataSize else 4 + dataSize
        val preChk = ByteArray(preChkSize)
        preChk[0] = lenByte.toByte()
        preChk[1] = frame.src.toByte()
        preChk[2] = frame.dst.toByte()
        var offset: Int
        if (cmdPresent) {
            preChk[3] = (frame.cmd!! and 0xFF).toByte()
            preChk[4] = frame.param.toByte()
            offset = 5
        } else {
            preChk[3] = frame.param.toByte()
            offset = 4
        }
        System.arraycopy(frame.data, 0, preChk, offset, dataSize)

        val chk = NinebotCodec.checksum(preChk)

        // Assemble post-prefix plaintext, then XOR-obscure.
        val postPrefix = ByteArray(preChk.size + 2)
        System.arraycopy(preChk, 0, postPrefix, 0, preChk.size)
        postPrefix[preChk.size] = chk[0]
        postPrefix[preChk.size + 1] = chk[1]
        NinebotCodec.xorInPlace(postPrefix, gamma)

        // Prepend the literal `55 AA`.
        val wire = ByteArray(2 + postPrefix.size)
        wire[0] = PREFIX[0]
        wire[1] = PREFIX[1]
        System.arraycopy(postPrefix, 0, wire, 2, postPrefix.size)
        return wire
    }

    // --- Convenience builders ----------------------------------------

    /**
     * Build a `GetKey` (§5.3) handshake request: the host asks the
     * KeyGenerator endpoint to send a 16-byte session keystream.
     *
     * Per §5.3 the handshake itself travels with γ = all-zero, so
     * the default [gamma] value is deliberately the zero keystream.
     * Once the response is parsed, callers should adopt the returned
     * 16 bytes as γ for all subsequent traffic.
     */
    fun getKey(
        srcHostApp: Int = ADDR_HOST_APP_DEFAULT,
        gamma: ByteArray = NinebotCodec.ZERO_KEYSTREAM,
    ): ByteArray = build(
        NinebotFrame(
            family = NinebotFamily.N2,
            src = srcHostApp,
            dst = ADDR_KEY_GENERATOR,
            cmd = CMD_GET_KEY,
            param = PARAM_GET_KEY,
            data = ByteArray(0),
        ),
        gamma,
    )

    /**
     * Build an N2 Read request (`CMD = 0x01`) for the given
     * [param]. [readLength] is the number of bytes to read, carried
     * as a single-byte DATA payload per common practice.
     */
    fun readParam(
        param: Int,
        readLength: Int = 1,
        srcHostApp: Int = ADDR_HOST_APP_DEFAULT,
        dst: Int = ADDR_CONTROLLER,
        gamma: ByteArray = NinebotCodec.ZERO_KEYSTREAM,
    ): ByteArray {
        require(readLength in 0..255) { "readLength out of range" }
        return build(
            NinebotFrame(
                family = NinebotFamily.N2,
                src = srcHostApp,
                dst = dst,
                cmd = CMD_READ,
                param = param,
                data = byteArrayOf(readLength.toByte()),
            ),
            gamma,
        )
    }

    /** Build an N2 Write request (`CMD = 0x03`) for the given [param] + payload. */
    fun writeParam(
        param: Int,
        payload: ByteArray,
        srcHostApp: Int = ADDR_HOST_APP_DEFAULT,
        dst: Int = ADDR_CONTROLLER,
        gamma: ByteArray = NinebotCodec.ZERO_KEYSTREAM,
    ): ByteArray = build(
        NinebotFrame(
            family = NinebotFamily.N2,
            src = srcHostApp,
            dst = dst,
            cmd = CMD_WRITE,
            param = param,
            data = payload,
        ),
        gamma,
    )

    /** Build an N1 frame (no CMD byte; identity keystream). */
    fun n1Frame(
        src: Int,
        dst: Int,
        param: Int,
        data: ByteArray = ByteArray(0),
    ): ByteArray = build(
        NinebotFrame(
            family = NinebotFamily.N1,
            src = src,
            dst = dst,
            cmd = null,
            param = param,
            data = data,
        ),
        NinebotCodec.ZERO_KEYSTREAM,
    )
}
