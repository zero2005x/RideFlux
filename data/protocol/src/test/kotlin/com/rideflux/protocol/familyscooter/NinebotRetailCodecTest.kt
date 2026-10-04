package com.rideflux.protocol.familyscooter

import com.rideflux.domain.safety.DangerTier
import com.rideflux.domain.safety.MotionInterlock
import com.rideflux.protocol.familyscooter.ninebot.NinebotRetailCodec
import com.rideflux.protocol.testutil.hex
import org.junit.Assert.*
import org.junit.Test

class NinebotRetailCodecTest {
    @Test fun `checksum is plain 16-bit inverted sum even above 15-bit boundary`() {
        assertEquals(0x7f80, RetailFraming.checksum(ByteArray(129) { 0xff.toByte() }))
    }

    @Test fun `SHU read vector has LE16 request size and includes length in checksum`() {
        assertArrayEquals(hex("5A A5 02 3E 20 01 22 02 00 7A FF"),
            NinebotRetailCodec.buildReadRequest(0x22, 2))
        val large = NinebotRetailCodec.buildReadRequest(0x22, 0x1234)
        assertArrayEquals(hex("34 12"), large.copyOfRange(7, 9))
        assertEquals(11, large.size)
        assertThrows(IllegalArgumentException::class.java) {
            NinebotRetailCodec.buildReadRequest(0x22, 0)
        }
    }

    @Test fun `SHU BLE random request matches independently derived vector`() {
        assertArrayEquals(hex("5A A5 00 3E 04 5B 00 62 FF"),
            NinebotRetailCodec.buildHandshakeStep1())
    }

    @Test fun `length and checksum reject corruption without partial telemetry`() {
        // 02+23+3E+22+04+41+00 = 0xCA; complement = FF35, little-endian.
        val reply = hex("5A A5 02 23 3E 22 04 41 00 35 FF")
        assertArrayEquals(hex("41 00"), NinebotRetailCodec.readReplyPayload(reply, 0x22, 2))
        assertNull(NinebotRetailCodec.readReplyPayload(reply, 0x23, 2))
        assertNull(NinebotRetailCodec.decodeFrame(reply.copyOf(reply.size - 1)))
        assertNull(NinebotRetailCodec.decodeFrame(reply.copyOf().apply { this[2] = 1 }))
        assertNull(NinebotRetailCodec.decodeFrame(reply.copyOf().apply { this[8] = 1 }))
        assertNull(NinebotRetailCodec.decodeFrame(reply.copyOf().apply { this[9] = 0 }))
    }

    @Test fun `critical pairing and lock controls require stationary interlock`() {
        val gate = MotionInterlock()
        val random = ByteArray(16) { it.toByte() }
        assertThrows(SecurityException::class.java) {
            NinebotRetailCodec.buildHandshakeStep2(random, gate, 100)
        }
        repeat(3) { gate.observe(0f, 100 + it * 100L) }
        val step2 = NinebotRetailCodec.buildHandshakeStep2(random, gate, 300)
        assertEquals(0x5c, NinebotRetailCodec.decodeFrame(step2)?.command)
        assertEquals(16, NinebotRetailCodec.decodeFrame(step2)?.payload?.size)
        assertEquals(DangerTier.CRITICAL, NinebotRetailCodec.controlTier(0x70))
        NinebotRetailCodec.requireControlAllowed(0x70, gate, 300)
        gate.observe(1f, 400)
        assertThrows(SecurityException::class.java) {
            NinebotRetailCodec.buildHandshakeStep3(byteArrayOf(1), gate, 400)
        }
        assertThrows(SecurityException::class.java) {
            NinebotRetailCodec.requireControlAllowed(0x71, gate, 400)
        }
        assertEquals(DangerTier.FORBIDDEN, NinebotRetailCodec.controlTier(0x78))
        assertThrows(SecurityException::class.java) {
            NinebotRetailCodec.requireControlAllowed(0x78, gate, 400)
        }
    }

    @Test fun `ES2 block leaves speed unknown until scale is verified`() {
        val payload = ByteArray(32)
        payload[8] = 80
        payload[10] = 42
        val block = requireNotNull(NinebotRetailCodec.decodeEs2B0(payload))
        assertEquals(42, block.speedRaw)
        assertNull(block.toTelemetry(1).speedKmh)
    }
}
