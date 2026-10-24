package com.rideflux.protocol.familyvesc

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VescSetupCodecTest {
    private val synthetic = hex(
        "02462F00FD0000000004D20000023701F4000007D0000061A801F8032000000000000000000000000000000000000000000000000000000000000000000000000000303900000000192003",
    )

    @Test fun `read poll matches upstream XMODEM vector`() {
        assertArrayEquals(hex("02 01 2F D5 8D 03"), VescSetupCodec.setupPoll())
    }

    @Test fun `setup telemetry checks length CRC and physical values`() {
        val values = requireNotNull(VescSetupCodec.decode(synthetic))
        assertEquals(2_000, values.motorRpm)
        assertEquals(12.34f, values.motorCurrentA, 0.01f)
        assertEquals(5.67f, values.inputCurrentA, 0.01f)
        assertEquals(50.4f, values.inputVoltageV, 0.01f)
        assertEquals(50f, values.dutyPercent, 0.01f)
        // Payload speed word is 0x000061A8 = 25000; the firmware scale is
        // 1e3 over a metres-per-second value, so this is 25 m/s (= 90 km/h),
        // NOT 25 km/h. Name and unit are asserted together on purpose.
        assertEquals(25f, values.speedMetresPerSecond, 0.01f)
        assertEquals(80f, values.batteryPercent, 0.01f)
        assertEquals(12_345L, values.odometerMetres)
        assertNull(VescSetupCodec.decode(synthetic.copyOf(synthetic.size - 1)))
        val corrupt = synthetic.copyOf().apply { this[20] = (this[20].toInt() xor 1).toByte() }
        assertNull(VescSetupCodec.decode(corrupt))
    }
}
