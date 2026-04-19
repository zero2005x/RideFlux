/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.testutil

/**
 * Parse a hexadecimal test-vector string (whitespace and newlines
 * ignored) into a [ByteArray]. Kept minimal because clean-room tests
 * must be independently auditable.
 */
internal fun hex(text: String): ByteArray {
    val clean = text.filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "Odd-length hex input: ${clean.length}" }
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(clean[i * 2], 16)
        val lo = Character.digit(clean[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "Non-hex character at $i" }
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
