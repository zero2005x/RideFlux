/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi2

/**
 * Byte-stuffing and 8-bit XOR checksum for Family I2
 * (`PROTOCOL_SPEC.md` §2.7.3 and §6.4.2).
 *
 * I2 escapes **two** byte values — `0xAA` and `0xA5` — each by
 * prefixing the payload byte with `0xA5`. Unlike I1, `0x55` is
 * **not** escaped (there is no `55 55` trailer). The CHECK byte
 * following the escaped body is transmitted raw.
 */
internal object InmotionI2Codec {

    const val PREAMBLE_BYTE: Byte = 0xAA.toByte()
    const val ESCAPE_BYTE: Byte = 0xA5.toByte()

    /** §2.7.2 FLAGS values. */
    const val FLAGS_INIT: Int = 0x11
    const val FLAGS_DEFAULT: Int = 0x14

    private fun mustEscape(u: Int): Boolean = u == 0xAA || u == 0xA5

    /**
     * Escape (byte-stuff) [body] per §2.7.3. Each occurrence of
     * `0xAA` or `0xA5` is emitted as `A5 x`; everything else passes
     * through.
     */
    fun escape(body: ByteArray): ByteArray {
        var extras = 0
        for (b in body) if (mustEscape(b.toInt() and 0xFF)) extras++
        val out = ByteArray(body.size + extras)
        var j = 0
        for (b in body) {
            if (mustEscape(b.toInt() and 0xFF)) out[j++] = ESCAPE_BYTE
            out[j++] = b
        }
        return out
    }

    /** Unsigned 8-bit XOR checksum across [body] (§6.4.2). */
    fun xorChecksum(body: ByteArray): Byte = xorChecksum8(body).toByte()

    /** [xorChecksum] as unsigned `Int` in `0..255`. */
    fun xorChecksum8(body: ByteArray): Int {
        var x = 0
        for (b in body) x = x xor (b.toInt() and 0xFF)
        return x
    }
}
