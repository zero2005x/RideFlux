package com.wheellog.next.data.protocol.decoder

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteUtilsTest {

    // ---- Big-Endian readers ----

    @Test
    fun `readUInt16BE reads correct value`() {
        val data = byteArrayOf(0x1E.toByte(), 0xA0.toByte())
        assertEquals(0x1EA0, ByteUtils.readUInt16BE(data, 0))
    }

    @Test
    fun `readUInt16BE with offset`() {
        val data = byteArrayOf(0x00, 0x20.toByte(), 0xD0.toByte())
        assertEquals(0x20D0, ByteUtils.readUInt16BE(data, 1))
    }

    @Test
    fun `readInt16BE reads positive value`() {
        val data = byteArrayOf(0x0B.toByte(), 0xB8.toByte())
        assertEquals(3000, ByteUtils.readInt16BE(data, 0))
    }

    @Test
    fun `readInt16BE reads negative value`() {
        // -1000 = 0xFC18
        val data = byteArrayOf(0xFC.toByte(), 0x18.toByte())
        assertEquals(-1000, ByteUtils.readInt16BE(data, 0))
    }

    @Test
    fun `readUInt32BE reads correct value`() {
        val data = byteArrayOf(0x00, 0x01.toByte(), 0xE2.toByte(), 0x40.toByte())
        assertEquals(123456L, ByteUtils.readUInt32BE(data, 0))
    }

    @Test
    fun `readInt32BE reads correct signed value`() {
        // -1 = 0xFFFFFFFF
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, ByteUtils.readInt32BE(data, 0))
    }

    // ---- Little-Endian readers ----

    @Test
    fun `readUInt16LE reads correct value`() {
        val data = byteArrayOf(0xA0.toByte(), 0x1E.toByte())
        assertEquals(0x1EA0, ByteUtils.readUInt16LE(data, 0))
    }

    @Test
    fun `readInt16LE reads positive value`() {
        val data = byteArrayOf(0xB8.toByte(), 0x0B.toByte())
        assertEquals(3000, ByteUtils.readInt16LE(data, 0))
    }

    @Test
    fun `readInt16LE reads negative value`() {
        // -1000 = 0xFC18 LE => 18 FC
        val data = byteArrayOf(0x18.toByte(), 0xFC.toByte())
        assertEquals(-1000, ByteUtils.readInt16LE(data, 0))
    }

    @Test
    fun `readUInt32LE reads correct value`() {
        val data = byteArrayOf(0x40.toByte(), 0xE2.toByte(), 0x01.toByte(), 0x00.toByte())
        assertEquals(123456L, ByteUtils.readUInt32LE(data, 0))
    }

    @Test
    fun `readInt32LE reads negative value`() {
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, ByteUtils.readInt32LE(data, 0))
    }

    // ---- KingSong getInt2R / getInt4R ----

    @Test
    fun `getInt2R reverses 2 bytes and reads as uint16`() {
        // bytes: [0xA0, 0x1E] reversed => [0x1E, 0xA0] => 0x1EA0 = 7840
        val data = byteArrayOf(0xA0.toByte(), 0x1E.toByte())
        assertEquals(0x1EA0, ByteUtils.getInt2R(data, 0))
    }

    @Test
    fun `getInt2RSigned reads signed reversed value`() {
        // 0xFFFF reversed is still 0xFFFF => -1
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, ByteUtils.getInt2RSigned(data, 0))
    }

    @Test
    fun `getInt2RSigned reads positive reversed value`() {
        // bytes: [0xF6, 0x09] reversed => [0x09, 0xF6] => 0x09F6 = 2550
        val data = byteArrayOf(0xF6.toByte(), 0x09.toByte())
        assertEquals(2550, ByteUtils.getInt2RSigned(data, 0))
    }

    @Test
    fun `getInt4R reverses every 2 bytes and reads as uint32`() {
        // bytes: [0x94, 0xD5, 0x12, 0x00] => reversed pairs: [0xD5,0x94, 0x00,0x12]
        // => 0xD594_0012 ... wait let me recalculate.
        // getInt4R: swap [0,1] and [2,3]:
        // [0x00, 0x12, 0xD5, 0x94] => reversed pairs: [0x12, 0x00, 0x94, 0xD5]
        // => 0x12009_4D5 = ... let's use simpler values.
        // [0x02, 0x01, 0x04, 0x03] => reversed: [0x01, 0x02, 0x03, 0x04]
        // => 0x01020304 = 16909060
        val data = byteArrayOf(0x02, 0x01, 0x04, 0x03)
        assertEquals(16909060L, ByteUtils.getInt4R(data, 0))
    }

    @Test
    fun `getInt4R with offset`() {
        val data = byteArrayOf(0xFF.toByte(), 0x02, 0x01, 0x04, 0x03)
        assertEquals(16909060L, ByteUtils.getInt4R(data, 1))
    }

    // ---- intRevBE ----

    @Test
    fun `intRevBE is same as getInt4R`() {
        val data = byteArrayOf(0x02, 0x01, 0x04, 0x03)
        assertEquals(ByteUtils.getInt4R(data, 0), ByteUtils.intRevBE(data, 0))
    }
}
