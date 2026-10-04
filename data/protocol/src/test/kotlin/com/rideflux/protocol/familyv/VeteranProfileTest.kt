package com.rideflux.protocol.familyv

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeteranProfileTest {
    @Test fun `default decoder profile remains legacy`() {
        val bytes = hex("DC5A5C2025CD0000071F0000C77800280000110B0E1000010AF00AF00422000300140000")
        val result = VeteranDecoder.decode(bytes)
        assertTrue(result is VeteranDecoder.DecodeResult.Ok)
        assertEquals(VeteranProtocolProfile.LEGACY, (result as VeteranDecoder.DecodeResult.Ok).frame.protocolProfile)
    }

    @Test fun `modern profile decodes vendor byte order and avoids output as PWM`() {
        val bytes = hex("DC5A5C2025CD0000071F0000C77800280000110B0E1000010AF00AF00422000300140000")
        // 0x07A5E8 = 501224 decimal -> hardware key 5012; use 0x07A5=1957 and 0x18 => 501016.
        bytes[30] = 0x07
        bytes[28] = 0xA5.toByte()
        bytes[29] = 0x18
        bytes[22] = 0x55
        bytes[23] = 0x02
        bytes[31] = 0x03
        val result = VeteranDecoder.decode(bytes, profile = VeteranProtocolProfile.MODERN_NOSFET)
        assertTrue(result is VeteranDecoder.DecodeResult.Ok)
        val frame = (result as VeteranDecoder.DecodeResult.Ok).frame
        assertEquals(36, result.consumedBytes)
        assertEquals(2, frame.chargeMode)
        assertEquals(3, frame.pedalsMode)
        assertEquals("5010", frame.hardwareKey)
        assertEquals("Apex", VeteranModelRegistry.fromHardwareKey(frame.hardwareKey)?.name)
        assertEquals(frame.hardwarePwmHundredthsPercent, frame.outputRaw)
    }

    @Test fun `unknown model key remains unknown`() {
        assertNull(VeteranModelRegistry.fromHardwareKey("6661"))
        assertEquals(30, VeteranModelRegistry.fromHardwareKey("5020")?.seriesCells)
    }
}
