package com.rideflux.protocol.familyscooter

import com.rideflux.protocol.familyscooter.m365.M365Codec
import org.junit.Assert.*
import org.junit.Test

class M365CodecTest {
    @Test fun documentedB0ReadHasMandatoryLengthAndExactChecksum() {
        assertArrayEquals(hex("55 AA 03 20 01 B0 20 0B FF"), M365Codec.b0BlockRequest())
        assertArrayEquals(hex("55 AA 03 20 01 22 02 B7 FF"), M365Codec.readRequest(0x22, 2))
    }

    @Test fun wordOffsetsDecodeOnlyFieldsPresentInB0() {
        val payload = ByteArray(32)
        fun word(reg: Int, value: Int) {
            val at = 2 * (reg - 0xb0)
            payload[at] = value.toByte()
            payload[at + 1] = (value ushr 8).toByte()
        }
        word(0xb0, 0x1234)
        word(0xb1, 7)
        word(0xb4, 65)
        word(0xb5, 2500)
        word(0xb7, 0x2345)
        word(0xb8, 0x0001)
        word(0xb9, 123)
        word(0xbb, 245)
        val block = M365Codec.decodeB0(payload)!!
        assertEquals(0x1234, block.errorCode)
        assertEquals(65, block.batteryPercent)
        assertEquals(2500, block.speedRaw)
        assertEquals(74565L, block.totalDistanceMetres)
        assertEquals(1230, block.tripDistanceMetres)
        assertEquals(2.5f, block.speedKmh!!, 0.001f)
        assertEquals(2.5f, block.toTelemetry(10).speedKmh!!, 0.001f)
        assertEquals(74565L, block.toTelemetry(10).totalDistanceMetres)
        word(0xb4, 101)
        assertNull(M365Codec.decodeB0(payload))
    }

    @Test fun invalidSpeedSentinelsRemainUnknownEvenWhenWheelMayBeTurning() {
        val payload = ByteArray(32)
        for (raw in listOf(0xff00, 0xff3e, 0xfff4, 0xffff)) {
            payload[10] = raw.toByte()
            payload[11] = (raw ushr 8).toByte()
            val block = requireNotNull(M365Codec.decodeB0(payload))
            assertNull(block.speedKmh)
            assertNull(block.toTelemetry(1).speedKmh)
        }
        payload[10] = 0
        payload[11] = 0
        assertEquals(0f, requireNotNull(M365Codec.decodeB0(payload)).speedKmh!!, 0f)
    }

    @Test fun replyRejectsBadChecksumAndWrongLength() {
        val data = ByteArray(32)
        val body = byteArrayOf(34, 0x23, 1, 0xb0.toByte()) + data
        val frame = RetailFraming.appendChecksum(hex("55 AA") + body, body)
        assertArrayEquals(data, M365Codec.readReplyPayload(frame, 0xb0, 32))
        assertNull(M365Codec.readReplyPayload(frame.dropLast(1).toByteArray(), 0xb0, 32))
        frame[9] = 1
        assertNull(M365Codec.readReplyPayload(frame, 0xb0, 32))
    }

    @Test fun voltageAndCurrentRequireSeparateReads() {
        assertEquals(36.54f, M365Codec.decodeBatteryVoltageV(hex("46 0E"))!!, 0.001f)
        assertEquals(0x1234, M365Codec.decodeBatteryCurrentRaw(hex("34 12")))
        assertNull(M365Codec.decodeBatteryVoltageV(byteArrayOf(1)))
    }

    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
