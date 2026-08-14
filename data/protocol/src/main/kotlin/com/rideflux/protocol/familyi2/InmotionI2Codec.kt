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
 *
 * **Usage contract:** [escape] must only ever be applied to the *raw
 * body* of a frame — never to an already-framed message and never to
 * the preamble/CHECK bytes (they are transmitted raw). Because `0xAA`
 * is both the preamble and an escape trigger, applying [escape] to a
 * full frame would corrupt it (the raw `0xAA` preamble would become
 * `A5 AA`). Likewise [xorChecksum] must be computed over the raw,
 * unescaped body; the decoder verifies the CHECK against the
 * unstuffed body, so escaping first would silently break the checksum
 * contract of §6.4.2.
 */
internal object InmotionI2Codec {

    const val PREAMBLE_BYTE: Byte = 0xAA.toByte()
    const val ESCAPE_BYTE: Byte = 0xA5.toByte()

    /** §2.7.2 FLAGS values. */
    const val FLAGS_INIT: Int = 0x11
    const val FLAGS_DEFAULT: Int = 0x14

    private fun mustEscape(u: Int): Boolean = u == 0xAA || u == 0xA5

    /**
     * Escape (byte-stuff) the *raw body* per §2.7.3. Each occurrence of
     * `0xAA` or `0xA5` is emitted as `A5 x`; everything else passes
     * through. Must not be applied to a full framed message (the
     * preamble `0xAA` would be escaped and corrupt the frame).
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

    /**
     * Unsigned 8-bit XOR checksum across the *raw, unescaped* [body]
     * (§6.4.2). Returned as an unsigned `Int` in `0..255`. The decoder
     * verifies the CHECK against the unstuffed body, so passing an
     * already-escaped body here would silently produce a CHECK that
     * does not match the spec.
     */
    fun xorChecksum(body: ByteArray): Int = xorChecksum8(body)

    /** [xorChecksum] as unsigned `Int` in `0..255`. */
    fun xorChecksum8(body: ByteArray): Int {
        var x = 0
        for (b in body) x = x xor (b.toInt() and 0xFF)
        return x
    }
}
