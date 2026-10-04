package com.rideflux.protocol.familybms.jbd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JbdCodecRejectionTest {
    /** Builds a frame with a valid length and checksum around [payload]. */
    private fun frame(register: Int, payload: ByteArray, status: Int = 0): ByteArray {
        val body = byteArrayOf(status.toByte(), payload.size.toByte()) + payload
        val checksum = (0x10000 - body.sumOf { it.toInt() and 0xff }) and 0xffff
        return byteArrayOf(0xdd.toByte(), register.toByte()) + body +
            byteArrayOf((checksum ushr 8).toByte(), checksum.toByte(), 0x77)
    }

    private fun basicPayload(soc: Int = 80, mos: Int = 3, cells: Int = 4, temps: Int = 2,
                             size: Int = 23 + 2 * temps): ByteArray {
        val p = ByteArray(size)
        p[19] = soc.toByte(); p[20] = mos.toByte(); p[21] = cells.toByte(); p[22] = temps.toByte()
        for (i in 0 until temps) {
            val kelvin = 2731 + 250
            if (23 + i * 2 + 1 < size) { p[23 + i * 2] = (kelvin ushr 8).toByte(); p[24 + i * 2] = kelvin.toByte() }
        }
        return p
    }

    private fun cellPayload(vararg mv: Int): ByteArray =
        mv.flatMap { listOf((it ushr 8).toByte(), it.toByte()) }.toByteArray()

    @Test fun `read request supports only the two known registers`() {
        assertArrayEquals(byteArrayOf(0xdd.toByte(), 0xa5.toByte(), 4, 0, 0xff.toByte(), 0xfc.toByte(), 0x77),
            JbdCodec.readRequest(4))
        assertThrows(IllegalArgumentException::class.java) { JbdCodec.readRequest(5) }
    }

    @Test fun `envelope violations are rejected`() {
        val good = frame(0x03, basicPayload())
        assertNotNull(JbdCodec.decode(good, 1))
        assertNull(JbdCodec.decode(good.copyOf(6), 1))
        assertNull(JbdCodec.decode(good.copyOf().apply { this[0] = 0 }, 1))
        assertNull(JbdCodec.decode(good.copyOf().apply { this[lastIndex] = 0 }, 1))
        assertNull(JbdCodec.decode(good + byteArrayOf(0), 1))
        assertNull(JbdCodec.decode(frame(0x03, basicPayload(), status = 1), 1))
        assertNull(JbdCodec.decode(good.copyOf().apply { this[5] = (this[5] + 1).toByte() }, 1))
        assertNull(JbdCodec.decode(frame(0x05, basicPayload()), 1))
    }

    @Test fun `basic frame reports mos switch bits independently`() {
        assertTrue(JbdCodec.decode(frame(0x03, basicPayload(mos = 1)), 1)!!.chargeMosEnabled!!)
        assertFalse(JbdCodec.decode(frame(0x03, basicPayload(mos = 1)), 1)!!.dischargeMosEnabled!!)
        assertTrue(JbdCodec.decode(frame(0x03, basicPayload(mos = 2)), 1)!!.dischargeMosEnabled!!)
        assertFalse(JbdCodec.decode(frame(0x03, basicPayload(mos = 0)), 1)!!.chargeMosEnabled!!)
        assertEquals(0, JbdCodec.decode(frame(0x03, basicPayload(temps = 0)), 1)!!.temperatures.size)
    }

    @Test fun `basic frame shape violations are rejected`() {
        assertNull(JbdCodec.decode(frame(0x03, ByteArray(22)), 1))
        assertNull(JbdCodec.decode(frame(0x03, basicPayload(cells = 0)), 1))
        assertNull(JbdCodec.decode(frame(0x03, basicPayload(cells = 33)), 1))
        assertNotNull(JbdCodec.decode(frame(0x03, basicPayload(cells = 32)), 1))
        assertNull(JbdCodec.decode(frame(0x03, basicPayload(temps = 17)), 1))
        assertNull(JbdCodec.decode(frame(0x03, basicPayload(temps = 2, size = 28)), 1))
        assertNull(JbdCodec.decode(frame(0x03, basicPayload(soc = 101)), 1))
        assertNotNull(JbdCodec.decode(frame(0x03, basicPayload(soc = 100)), 1))
    }

    @Test fun `cell frame validates count and millivolt range`() {
        val two = JbdCodec.decode(frame(0x04, cellPayload(3300, 3301)), 1)
        assertEquals(listOf(3300, 3301), two!!.cellVoltages.map { it.value })
        assertNotNull(JbdCodec.decode(frame(0x04, cellPayload(1000, 5000)), 1))
        assertNull(JbdCodec.decode(frame(0x04, ByteArray(0)), 1))
        assertNull(JbdCodec.decode(frame(0x04, ByteArray(3)), 1))
        assertNull(JbdCodec.decode(frame(0x04, ByteArray(66)), 1))
        assertNull(JbdCodec.decode(frame(0x04, cellPayload(3300, 0)), 1))
        assertNull(JbdCodec.decode(frame(0x04, cellPayload(999, 3300)), 1))
        assertNull(JbdCodec.decode(frame(0x04, cellPayload(3300, 5001)), 1))
    }
}
