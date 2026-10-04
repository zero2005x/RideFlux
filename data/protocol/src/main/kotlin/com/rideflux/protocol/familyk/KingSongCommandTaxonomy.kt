/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyk

/** Counts are a vendor-static census of fx.java builders, not supported EUC commands. */
object KingSongCommandTaxonomy {
    const val FIXED_TAIL_BUILDERS = 107
    const val CHECKSUM_TAILED_BUILDERS = 34
    const val UNCLASSIFIED_BUILDERS = 52
    const val TOTAL_BUILDERS = 193

    enum class TailClass { FIXED_5A5A, CRC16_CCITT_CANDIDATE, UNCLASSIFIED }

    /** Describes the shape only; a matching CRC never grants command capability. */
    fun classify(frame: ByteArray): TailClass {
        if (frame.size != 20 || frame[0] != 0xaa.toByte() || frame[1] != 0x55.toByte()) {
            return TailClass.UNCLASSIFIED
        }
        if (frame[18] == 0x5a.toByte() && frame[19] == 0x5a.toByte()) return TailClass.FIXED_5A5A
        // The vendor N() checksum runs over a 16-byte template, copied to offsets 2..17.
        val expected = crc16Ccitt(frame, 2, 18)
        val actual = ((frame[18].toInt() and 0xff) shl 8) or (frame[19].toInt() and 0xff)
        return if (expected == actual) TailClass.CRC16_CCITT_CANDIDATE else TailClass.UNCLASSIFIED
    }

    /** CCITT polynomial 0x1021; initial value follows the vendor's N() routine. */
    fun crc16Ccitt(bytes: ByteArray, start: Int, endExclusive: Int): Int {
        require(start in 0..endExclusive && endExclusive <= bytes.size)
        var crc = 0
        for (i in start until endExclusive) {
            crc = crc xor ((bytes[i].toInt() and 0xff) shl 8)
            repeat(8) { crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xffff else (crc shl 1) and 0xffff }
        }
        return crc
    }
}
