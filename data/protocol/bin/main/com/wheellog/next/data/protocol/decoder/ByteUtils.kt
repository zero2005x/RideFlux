package com.wheellog.next.data.protocol.decoder

/**
 * Shared byte-reading helpers used by all protocol decoders.
 *
 * Includes standard Big-Endian / Little-Endian readers and
 * KingSong-specific "reverse every 2 bytes" (getInt2R / getInt4R) readers.
 */
object ByteUtils {

    // ---- Big-Endian readers ----

    fun readUInt16BE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    fun readInt16BE(bytes: ByteArray, offset: Int): Int {
        val raw = readUInt16BE(bytes, offset)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    fun readUInt32BE(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    fun readInt32BE(bytes: ByteArray, offset: Int): Int {
        val raw = readUInt32BE(bytes, offset)
        return raw.toInt()
    }

    // ---- Little-Endian readers ----

    fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    fun readInt16LE(bytes: ByteArray, offset: Int): Int {
        val raw = readUInt16LE(bytes, offset)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    fun readInt32LE(bytes: ByteArray, offset: Int): Int {
        val raw = readUInt32LE(bytes, offset)
        return raw.toInt()
    }

    // ---- KingSong "reverse every 2 bytes" readers ----

    /**
     * Read a 2-byte value using KingSong's reverse-every-2-bytes scheme (getInt2R).
     * Swaps bytes[offset] and bytes[offset+1] before reading as big-endian int16.
     */
    fun getInt2R(bytes: ByteArray, offset: Int): Int {
        val reversed = byteArrayOf(bytes[offset + 1], bytes[offset])
        return ((reversed[0].toInt() and 0xFF) shl 8) or (reversed[1].toInt() and 0xFF)
    }

    /**
     * Signed version of [getInt2R].
     */
    fun getInt2RSigned(bytes: ByteArray, offset: Int): Int {
        val raw = getInt2R(bytes, offset)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    /**
     * Read a 4-byte value using KingSong's reverse-every-2-bytes scheme (getInt4R).
     * Swaps each pair: [offset, offset+1] and [offset+2, offset+3] before reading as big-endian int32.
     */
    fun getInt4R(bytes: ByteArray, offset: Int): Long {
        val reversed = byteArrayOf(
            bytes[offset + 1], bytes[offset],
            bytes[offset + 3], bytes[offset + 2],
        )
        return ((reversed[0].toLong() and 0xFF) shl 24) or
            ((reversed[1].toLong() and 0xFF) shl 16) or
            ((reversed[2].toLong() and 0xFF) shl 8) or
            (reversed[3].toLong() and 0xFF)
    }

    // ---- Veteran/Gotway "intRevBE" reader ----

    /**
     * Read a 4-byte value with Veteran byte ordering.
     * Each 2-byte pair is reversed before reading as big-endian int32.
     * Identical to [getInt4R] in practice.
     */
    fun intRevBE(bytes: ByteArray, offset: Int): Long = getInt4R(bytes, offset)
}
