/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyn

/**
 * XOR obfuscation and frame-checksum helpers for Families N1 / N2
 * (`PROTOCOL_SPEC.md` §5.1 and §6.1).
 *
 * These are primitive algorithms, not cryptographic primitives. The
 * session keystream is a 16-byte value supplied verbatim by the
 * device; there is no cryptographic strength to relying on it.
 */
internal object NinebotCodec {

    /** Size of the rolling keystream γ in bytes. */
    const val KEYSTREAM_SIZE: Int = 16

    /** All-zero keystream; used by N1 and before the N2 handshake. */
    val ZERO_KEYSTREAM: ByteArray = ByteArray(KEYSTREAM_SIZE)

    /**
     * Apply the 16-byte rolling XOR keystream to [buffer] in place.
     *
     * The buffer passed in is the **post-prefix** byte sequence
     * (i.e. everything after `55 AA`). Position 0 (LEN) is emitted
     * in the clear; position `j >= 1` is XORed with
     * `gamma[(j - 1) mod 16]`, per §5.1. The operation is
     * symmetric.
     *
     * [buffer] and [gamma] must not be the same array: mutating the
     * buffer in place while reading the keystream from the same
     * instance would progressively corrupt the keystream itself.
     */
    fun xorInPlace(buffer: ByteArray, gamma: ByteArray) {
        require(gamma.size == KEYSTREAM_SIZE) {
            "γ must be exactly $KEYSTREAM_SIZE bytes, got ${gamma.size}"
        }
        require(buffer !== gamma) { "buffer and gamma must not be the same array" }
        if (buffer.isEmpty()) return
        for (j in 1 until buffer.size) {
            buffer[j] = (buffer[j].toInt() xor gamma[(j - 1) % KEYSTREAM_SIZE].toInt()).toByte()
        }
    }

    /** Shared byte-wise sum over [bytes] used by both checksum helpers. */
    private fun frameSum(bytes: ByteArray): Int {
        var sum = 0
        for (b in bytes) sum += (b.toInt() and 0xFF)
        return sum
    }

    /**
     * Compute the two-byte frame checksum per §6.1 over the
     * pre-checksum byte sequence [bytes]. Returns a two-element
     * `ByteArray` `[CHK_LO, CHK_HI]` in transmission order.
     */
    fun checksum(bytes: ByteArray): ByteArray {
        val chk16 = (frameSum(bytes) xor 0xFFFF) and 0xFFFF
        return byteArrayOf(
            (chk16 and 0xFF).toByte(),
            ((chk16 ushr 8) and 0xFF).toByte(),
        )
    }

    /** Convenience 16-bit form for assertions / tests. */
    fun checksum16(bytes: ByteArray): Int =
        (frameSum(bytes) xor 0xFFFF) and 0xFFFF
}
