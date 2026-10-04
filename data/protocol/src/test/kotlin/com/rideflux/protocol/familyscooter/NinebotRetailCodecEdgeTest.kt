package com.rideflux.protocol.familyscooter

import com.rideflux.domain.safety.DangerTier
import com.rideflux.domain.safety.MotionInterlock
import com.rideflux.protocol.familyscooter.ninebot.NinebotRetailCodec
import com.rideflux.protocol.familyscooter.ninebot.NinebotRetailCodec.EscFamily
import com.rideflux.protocol.familyscooter.ninebot.NinebotRetailCodec.RegisterMeaning
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NinebotRetailCodecEdgeTest {
    private val now = 1_002L

    private fun open(): MotionInterlock = MotionInterlock().also { gate ->
        repeat(3) { gate.observe(0f, 1_000L + it) }
    }

    private fun reply(source: Int = 0x23, destination: Int = 0x3e, command: Int = 0xb0, argument: Int = 4,
                      payload: ByteArray = ByteArray(2)): ByteArray {
        val body = byteArrayOf(payload.size.toByte(), source.toByte(), destination.toByte(),
            command.toByte(), argument.toByte()) + payload
        return RetailFraming.appendChecksum(byteArrayOf(0x5a, 0xa5.toByte()) + body, body)
    }

    @Test fun `read request validates arguments and encodes the length little endian`() {
        assertThrows(IllegalArgumentException::class.java) { NinebotRetailCodec.buildReadRequest(-1) }
        assertThrows(IllegalArgumentException::class.java) { NinebotRetailCodec.buildReadRequest(256) }
        assertThrows(IllegalArgumentException::class.java) { NinebotRetailCodec.buildReadRequest(0x22, 0) }
        assertThrows(IllegalArgumentException::class.java) { NinebotRetailCodec.buildReadRequest(0x22, 0x10000) }
        assertThrows(IllegalArgumentException::class.java) { NinebotRetailCodec.buildReadRequest(0x22, 2, 256) }
        val frame = NinebotRetailCodec.buildReadRequest(0x22, 0x1234)
        assertEquals(0x34, frame[7].toInt())
        assertEquals(0x12, frame[8].toInt())
    }

    @Test fun `pairing steps need a valid random and a stationary permit`() {
        val random = ByteArray(16) { it.toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            NinebotRetailCodec.buildHandshakeStep2(ByteArray(15), open(), now)
        }
        assertThrows(SecurityException::class.java) {
            NinebotRetailCodec.buildHandshakeStep2(random, MotionInterlock(), now)
        }
        assertEquals(0x5c, NinebotRetailCodec.decodeFrame(
            NinebotRetailCodec.buildHandshakeStep2(random, open(), now))!!.command)
        assertThrows(IllegalArgumentException::class.java) {
            NinebotRetailCodec.buildHandshakeStep3(ByteArray(8), open(), now)
        }
        assertThrows(SecurityException::class.java) {
            NinebotRetailCodec.buildHandshakeStep3(random, MotionInterlock(), now)
        }
        val accept = NinebotRetailCodec.decodeFrame(NinebotRetailCodec.buildHandshakeStep3(random, open(), now))!!
        assertEquals(0x5d, accept.command)
        assertArrayEquals(random, accept.payload)
    }

    @Test fun `only the two lock registers have a danger tier below forbidden`() {
        assertEquals(DangerTier.CRITICAL, NinebotRetailCodec.controlTier(0x70))
        assertEquals(DangerTier.CRITICAL, NinebotRetailCodec.controlTier(0x71))
        for (register in listOf(0x78, 0x79, 0x07, 0x08, 0x09, 0x01, 0xff)) {
            assertEquals(DangerTier.FORBIDDEN, NinebotRetailCodec.controlTier(register))
            assertThrows(SecurityException::class.java) {
                NinebotRetailCodec.requireControlAllowed(register, open(), now)
            }
        }
        assertThrows(SecurityException::class.java) {
            NinebotRetailCodec.requireControlAllowed(0x70, MotionInterlock(), now)
        }
        NinebotRetailCodec.requireControlAllowed(0x71, open(), now)
    }

    @Test fun `write builder is limited to the lock value`() {
        val one = byteArrayOf(1, 0)
        assertThrows(IllegalArgumentException::class.java) {
            NinebotRetailCodec.buildWriteRequest(0x72, one, open(), now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NinebotRetailCodec.buildWriteRequest(0x70, byteArrayOf(2, 0), open(), now)
        }
        assertThrows(SecurityException::class.java) {
            NinebotRetailCodec.buildLockRequest(MotionInterlock(), now)
        }
        assertThrows(SecurityException::class.java) {
            NinebotRetailCodec.buildUnlockRequest(MotionInterlock(), now)
        }
        assertEquals(0x70, NinebotRetailCodec.decodeFrame(NinebotRetailCodec.buildLockRequest(open(), now))!!.argument)
        assertEquals(0x71, NinebotRetailCodec.decodeFrame(NinebotRetailCodec.buildUnlockRequest(open(), now))!!.argument)
    }

    @Test fun `frame decoding rejects every malformed shape`() {
        val good = reply()
        assertNotNull(NinebotRetailCodec.decodeFrame(good))
        assertNull(NinebotRetailCodec.decodeFrame(good.copyOf(8)))
        assertNull(NinebotRetailCodec.decodeFrame(good.copyOf().apply { this[0] = 0 }))
        assertNull(NinebotRetailCodec.decodeFrame(good.copyOf().apply { this[1] = 0 }))
        assertNull(NinebotRetailCodec.decodeFrame(good + byteArrayOf(0)))
        assertNull(NinebotRetailCodec.decodeFrame(good.copyOf().apply { this[lastIndex] = 0 }))
    }

    @Test fun `read replies must match source destination register and size`() {
        assertNotNull(NinebotRetailCodec.readReplyPayload(reply(), 0xb0, 2))
        assertNull(NinebotRetailCodec.readReplyPayload(reply(), -1, 2))
        assertNull(NinebotRetailCodec.readReplyPayload(reply(), 256, 2))
        assertNull(NinebotRetailCodec.readReplyPayload(reply(), 0xb0, 0))
        assertNull(NinebotRetailCodec.readReplyPayload(reply(), 0xb0, 256))
        assertNull(NinebotRetailCodec.readReplyPayload(byteArrayOf(1, 2, 3), 0xb0, 2))
        assertNull(NinebotRetailCodec.readReplyPayload(reply(source = 0x20), 0xb0, 2))
        assertNull(NinebotRetailCodec.readReplyPayload(reply(destination = 0x20), 0xb0, 2))
        assertNull(NinebotRetailCodec.readReplyPayload(reply(command = 0xb1), 0xb0, 2))
        assertNull(NinebotRetailCodec.readReplyPayload(reply(argument = 3), 0xb0, 2))
        assertNull(NinebotRetailCodec.readReplyPayload(reply(), 0xb0, 3))
    }

    @Test fun `ES2 B0 block and speed candidate decode only complete replies`() {
        val block = ByteArray(32).apply { this[8] = 55; this[10] = 0xe8.toByte(); this[11] = 0x03 }
        val frame = reply(payload = block)
        assertEquals(55, NinebotRetailCodec.decodeEs2B0(block)!!.batteryPercent)
        assertEquals(1f, NinebotRetailCodec.decodeSpeedKmh(frame)!!, 0.001f)
        assertNull(NinebotRetailCodec.decodeSpeedKmh(reply(command = 0xb1, payload = block)))
        assertNull(NinebotRetailCodec.decodeSpeedKmh(reply()))
    }

    @Test fun `register meaning depends on the ESC family`() {
        assertEquals(RegisterMeaning.BATTERY_CURRENT_RAW, NinebotRetailCodec.registerMeaning(EscFamily.ES2, 0x49))
        assertEquals(RegisterMeaning.EXTERNAL_BATTERY_TEMPERATURE_C,
            NinebotRetailCodec.registerMeaning(EscFamily.ES2, 0x50))
        assertEquals(RegisterMeaning.BATTERY_CURRENT_RAW, NinebotRetailCodec.registerMeaning(EscFamily.M365, 0x50))
        assertEquals(RegisterMeaning.UNKNOWN, NinebotRetailCodec.registerMeaning(EscFamily.M365, 0x49))
        assertEquals(RegisterMeaning.UNKNOWN, NinebotRetailCodec.registerMeaning(EscFamily.ES2, 0x01))
    }
}
