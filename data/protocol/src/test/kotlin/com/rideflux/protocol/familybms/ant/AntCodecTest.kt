package com.rideflux.protocol.familybms.ant

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AntCodecTest {
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

    @Test fun `poll has captured CRC vector`() {
        assertArrayEquals(hex("7EA1010000BE1855AA55"), AntCodec.statusPoll())
    }

    @Test fun `real 16S frame decodes and corruption rejects`() {
        val t = requireNotNull(AntCodec.decode(real16s, 7))
        assertEquals(16, t.cellVoltages.size)
        assertEquals(2, t.temperatures.size)
        assertEquals(52.84f, t.totalVoltageV!!, 0.01f)
        assertEquals(52_840, t.cellVoltages.sumOf { it.value })
        val bad = real16s.copyOf().apply { this[40] = (this[40].toInt() xor 1).toByte() }
        assertNull(AntCodec.decode(bad, 7))
        assertNull(AntCodec.decode(real16s.copyOf(real16s.size - 1), 7))
    }
}
