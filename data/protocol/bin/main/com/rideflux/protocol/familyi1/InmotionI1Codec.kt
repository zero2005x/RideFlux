/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi1

/**
 * Byte-stuffing and 8-bit additive checksum for Family I1
 * (`PROTOCOL_SPEC.md` §2.6.2 and §6.4.1).
 *
 * I1 escapes three byte values — `0xAA`, `0x55`, `0xA5` — each by
 * prefixing the payload byte with an escape byte `0xA5`. The CHECK
 * byte that follows the escaped body is transmitted raw (never
 * escaped). Preamble `AA AA` and trailer `55 55` are also raw.
 */
internal object InmotionI1Codec {

    const val PREAMBLE_BYTE: Byte = 0xAA.toByte()
    const val TRAILER_BYTE: Byte = 0x55.toByte()
    const val ESCAPE_BYTE: Byte = 0xA5.toByte()

    /** Byte values that must be escaped when they appear in the body. */
    private fun mustEscape(u: Int): Boolean =
        u == 0xAA || u == 0x55 || u == 0xA5

    /**
     * Escape (byte-stuff) [body] per §2.6.2. Each occurrence of
     * `0xAA`, `0x55`, or `0xA5` is emitted as `A5 x`; all other
     * bytes pass through unchanged.
     */
    fun escape(body: ByteArray): ByteArray {
        var extras = 0
        for (b in body) if (mustEscape(b.toInt() and 0xFF)) extras++
        val out = ByteArray(body.size + extras)
        var j = 0
        for (b in body) {
            if (mustEscape(b.toInt() and 0xFF)) {
                out[j++] = ESCAPE_BYTE
            }
            out[j++] = b
        }
        return out
    }

    /**
     * Inverse of [escape]. Consumes exactly [escapedLength] wire
     * bytes starting at [offset] in [wire] and returns the unstuffed
     * body.
     *
     * A wire `A5` acts as an escape marker: the byte that follows is
     * appended literally (so `A5 A5` unstuffs to a single `A5`). If
     * the wire region ends with a lone `A5`, [InmotionI1DecodeError.BadEscape]
     * is signalled via return value `null`.
     */
    fun unescape(wire: ByteArray, offset: Int, escapedLength: Int): ByteArray? {
        val end = offset + escapedLength
        require(end <= wire.size) { "unescape: out-of-range" }
        val out = ArrayList<Byte>(escapedLength)
        var i = offset
        while (i < end) {
            val b = wire[i]
            if (b == ESCAPE_BYTE) {
                if (i + 1 >= end) return null
                out.add(wire[i + 1])
                i += 2
            } else {
                out.add(b)
                i += 1
            }
        }
        return out.toByteArray()
    }

    /**
     * 8-bit additive checksum over the unstuffed body (§6.4.1):
     * `CHECK = (Σ body[i]) mod 256`.
     */
    fun checksum(body: ByteArray): Byte = checksum8(body).toByte()

    /** [checksum] as an unsigned `Int` in `0..255`. */
    fun checksum8(body: ByteArray): Int {
        var s = 0
        for (b in body) s = (s + (b.toInt() and 0xFF)) and 0xFF
        return s
    }
}
