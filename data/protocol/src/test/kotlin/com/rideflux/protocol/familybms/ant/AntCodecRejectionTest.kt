package com.rideflux.protocol.familybms.ant

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Mutates the captured 16S frame and re-seals the CRC so each rule is exercised alone. */
class AntCodecRejectionTest {
    private val real16s = hex("""
        7EA11100008E05010210000000000000
        00008000800100000000000000000000
        0000E40CE40CE50CE50CE80CE70CE70C
        E60CE80CE70CE70CE70CE70CE70CE60C
        E90C0100020002000700A41403005B00
        6400010100000076B010D5670E0FBA32
        4A000F00000010582E0200000000E90C
        1000E40C01000500E60C000080007A00
        0F02F2FAB98C3B00BBD85800DA2D4300
        E8B649000543AA55
    """)

    private fun crc(bytes: ByteArray, from: Int, until: Int): Int {
        var c = 0xffff
        for (i in from until until) {
            c = c xor (bytes[i].toInt() and 0xff)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xa001 else c ushr 1 }
        }
        return c
    }

    private fun seal(frame: ByteArray): ByteArray = frame.also {
        val c = crc(it, 1, it.size - 4)
        it[it.size - 4] = c.toByte()
        it[it.size - 3] = (c ushr 8).toByte()
    }

    /** Payload offset p lives at frame index 6 + p. */
    private fun mutate(vararg edits: Pair<Int, Int>): ByteArray {
        val f = real16s.copyOf()
        for ((payloadIndex, value) in edits) f[6 + payloadIndex] = value.toByte()
        return seal(f)
    }

    @Test fun `unmodified capture decodes`() {
        assertNotNull(AntCodec.decode(seal(real16s.copyOf()), 1))
    }

    @Test fun `envelope violations are rejected`() {
        assertNull(AntCodec.decode(real16s.copyOf(9), 1))
        assertNull(AntCodec.decode(real16s.copyOf().apply { this[0] = 0 }, 1))
        assertNull(AntCodec.decode(real16s.copyOf().apply { this[1] = 0 }, 1))
        assertNull(AntCodec.decode(real16s.copyOf().apply { this[lastIndex - 1] = 0 }, 1))
        assertNull(AntCodec.decode(real16s.copyOf().apply { this[lastIndex] = 0 }, 1))
        assertNull(AntCodec.decode(seal(real16s.copyOf().apply { this[2] = 0x12 }), 1))
        assertNull(AntCodec.decode(seal(real16s.copyOf().apply { this[5] = (this[5] - 1).toByte() }), 1))
    }

    @Test fun `short payload is rejected even with a valid CRC`() {
        val f = ByteArray(30)
        f[0] = 0x7e; f[1] = 0xa1.toByte(); f[2] = 0x11; f[5] = 20
        f[28] = 0xaa.toByte(); f[29] = 0x55
        assertNull(AntCodec.decode(seal(f), 1))
    }

    @Test fun `cell and temperature counts are range checked`() {
        assertNull(AntCodec.decode(mutate(2 to 9), 1))
        assertNull(AntCodec.decode(mutate(3 to 15), 1))
        assertNull(AntCodec.decode(mutate(3 to 33), 1))
    }

    @Test fun `physical plausibility rules reject inconsistent frames`() {
        assertNull(AntCodec.decode(mutate(28 to 0, 29 to 0), 1))
        assertNull(AntCodec.decode(mutate(28 to 0x89, 29 to 0x13), 1))
        assertNull(AntCodec.decode(mutate(68 to 0xff, 69 to 0x7f), 1))
        assertNull(AntCodec.decode(mutate(74 to 101, 75 to 0), 1))
    }

    @Test fun `switch flags and negative temperatures are decoded`() {
        val off = AntCodec.decode(mutate(76 to 0, 77 to 0), 1)!!
        assertFalse(off.chargeMosEnabled!!)
        assertFalse(off.dischargeMosEnabled!!)
        val cold = AntCodec.decode(mutate(60 to 0xff, 61 to 0xff), 1)!!
        assertEquals(-1f, cold.temperatures[0].value, 0f)
    }
}
