package com.rideflux.protocol.familyscooter

/** Retail framing helpers; Ninebot geometry follows SHU 4.2.1 Dart AOT (L1). */
internal object RetailFraming {
    fun checksum(bytes: ByteArray): Int {
        val sum = bytes.sumOf { it.toInt() and 0xff }
        return sum.inv() and 0xffff
    }

    fun appendChecksum(prefix: ByteArray, covered: ByteArray): ByteArray {
        val value = checksum(covered)
        return prefix + byteArrayOf(value.toByte(), (value ushr 8).toByte())
    }

    fun verify(frame: ByteArray, coveredFrom: Int): Boolean {
        if (frame.size < coveredFrom + 2) return false
        val value = checksum(frame.copyOfRange(coveredFrom, frame.size - 2))
        return frame[frame.size - 2].u() == (value and 0xff) &&
            frame.last().u() == (value ushr 8)
    }

    fun Byte.u(): Int = toInt() and 0xff
}
