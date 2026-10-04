package com.rideflux.protocol.familyvesc

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VescSetupCodecRejectionTest {
    private val synthetic = hex(
        "02462F00FD0000000004D20000023701F4000007D0000061A801F8032000000000000000000000000000000000000000000000000000000000000000000000000000303900000000192003",
    )

    /** Payload index p is frame index 2 + p; the CRC is re-sealed after each edit. */
    private fun mutate(vararg edits: Pair<Int, Int>): ByteArray {
        val f = synthetic.copyOf()
        for ((p, v) in edits) f[2 + p] = v.toByte()
        val crc = VescSetupCodec.crc16Xmodem(f.copyOfRange(2, 72))
        f[72] = (crc ushr 8).toByte(); f[73] = crc.toByte()
        return f
    }

    @Test fun `unmodified frame decodes`() {
        assertNotNull(VescSetupCodec.decode(mutate()))
    }

    @Test fun `envelope violations are rejected`() {
        assertNull(VescSetupCodec.decode(ByteArray(4)))
        assertNull(VescSetupCodec.decode(synthetic.copyOf().apply { this[0] = 0 }))
        assertNull(VescSetupCodec.decode(synthetic.copyOf().apply { this[lastIndex] = 0 }))
        assertNull(VescSetupCodec.decode(synthetic.copyOf().apply { this[1] = 69 }))
        assertNull(VescSetupCodec.decode(mutate(0 to 0x04)))
    }

    @Test fun `physical limits reject implausible values`() {
        assertNull(VescSetupCodec.decode(mutate(13 to 0x27, 14 to 0x10)))
        assertNull(VescSetupCodec.decode(mutate(25 to 0x03, 26 to 0xe9)))
        assertNull(VescSetupCodec.decode(mutate(25 to 0xff, 26 to 0xff)))
        assertNull(VescSetupCodec.decode(mutate(23 to 0x0b, 24 to 0xb9)))
        assertNull(VescSetupCodec.decode(mutate(19 to 0x00, 20 to 0x03, 21 to 0xd5, 22 to 0x88)))
    }

    @Test fun `negative duty and reverse speed are accepted`() {
        val values = VescSetupCodec.decode(mutate(13 to 0xfe, 14 to 0x0c, 19 to 0xff, 20 to 0xff, 21 to 0xf0, 22 to 0x60))!!
        assertEquals(-50f, values.dutyPercent, 0.01f)
        assertEquals(-4f, values.speedKmh, 0.01f)
    }
}
