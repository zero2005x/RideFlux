package com.rideflux.protocol.familyscooter

import com.rideflux.protocol.familyscooter.m365.M365Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class M365CodecEdgeTest {
    private fun reply(source: Int = 0x23, cmd: Int = 1, register: Int = 0xb0, data: ByteArray = ByteArray(4),
                      length: Int = data.size + 2, head: Int = 0x55, second: Int = 0xaa): ByteArray {
        val body = byteArrayOf(length.toByte(), source.toByte(), cmd.toByte(), register.toByte()) + data
        return RetailFraming.appendChecksum(byteArrayOf(head.toByte(), second.toByte()) + body, body)
    }

    @Test fun `read requests validate their arguments`() {
        assertThrows(IllegalArgumentException::class.java) { M365Codec.readRequest(-1, 2) }
        assertThrows(IllegalArgumentException::class.java) { M365Codec.readRequest(256, 2) }
        assertThrows(IllegalArgumentException::class.java) { M365Codec.readRequest(0x22, 0) }
        assertThrows(IllegalArgumentException::class.java) { M365Codec.readRequest(0x22, 256) }
        assertThrows(IllegalArgumentException::class.java) { M365Codec.readRequest(0x22, 2, 256) }
        assertEquals(9, M365Codec.readRequest(0x22, 2, 0x21).size)
    }

    @Test fun `reply parsing accepts only an exact ESC read reply`() {
        assertNotNull(M365Codec.readReplyPayload(reply(), 0xb0, 4))
        assertNull(M365Codec.readReplyPayload(reply(), -1, 4))
        assertNull(M365Codec.readReplyPayload(reply(), 256, 4))
        assertNull(M365Codec.readReplyPayload(reply(), 0xb0, 0))
        assertNull(M365Codec.readReplyPayload(reply(), 0xb0, 256))
        assertNull(M365Codec.readReplyPayload(reply(), 0xb0, 5))
        assertNull(M365Codec.readReplyPayload(reply(head = 0x56), 0xb0, 4))
        assertNull(M365Codec.readReplyPayload(reply(second = 0xab), 0xb0, 4))
        assertNull(M365Codec.readReplyPayload(reply(length = 7), 0xb0, 4))
        assertNull(M365Codec.readReplyPayload(reply(source = 0x20), 0xb0, 4))
        assertNull(M365Codec.readReplyPayload(reply(cmd = 2), 0xb0, 4))
        assertNull(M365Codec.readReplyPayload(reply(register = 0xb1), 0xb0, 4))
    }

    @Test fun `block and register decoders reject wrong sizes`() {
        assertNull(M365Codec.decodeB0(ByteArray(31)))
        assertNull(M365Codec.decodeBatteryCurrentRaw(byteArrayOf(1)))
        assertNull(M365Codec.decodeBatteryVoltageV(ByteArray(3)))
    }

    @Test fun `speed just below the sentinel is a real value and temperature keeps its sign`() {
        val payload = ByteArray(32)
        payload[10] = 0xff.toByte(); payload[11] = 0xfe.toByte()
        payload[22] = 0xf6.toByte(); payload[23] = 0xff.toByte()
        val block = M365Codec.decodeB0(payload)!!
        assertEquals(65.279f, block.speedKmh!!, 0.001f)
        assertEquals(-10, block.frameTemperatureRaw)
        assertEquals(M365Codec.NO_SPEED_SENTINEL, 0xFF00)
    }
}
