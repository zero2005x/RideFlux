package com.rideflux.protocol.familyi2

import com.rideflux.domain.codec.DecodeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InmotionModelRegistryTest {
    @Test fun `vendor matrix contains all 31 distinct models`() {
        assertEquals(31, InmotionModelRegistry.all.size)
        assertEquals(7, InmotionModelRegistry.all.count { it.generation == InmotionModelRegistry.Generation.EZCAN })
        assertEquals(24, InmotionModelRegistry.all.count { it.generation == InmotionModelRegistry.Generation.LORIN })
        assertEquals(54, InmotionModelRegistry.find("V12 Pro")?.minPayloadLength)
    }

    @Test fun `v11 and v11y cannot share a layout by substring`() {
        val v11 = requireNotNull(InmotionModelRegistry.find("V11"))
        val v11y = requireNotNull(InmotionModelRegistry.find("V11Y"))
        assertNotEquals(v11.minPayloadLength, v11y.minPayloadLength)
        assertEquals(21, v11.effectiveFieldCount)
        assertEquals(32, v11y.effectiveFieldCount)
        assertEquals("v11y", InmotionModelRegistry.find("V11Y-1")?.key)
        assertNull(InmotionModelRegistry.find("V11 mystery"))
    }

    @Test fun `production codec does not publish V11 speed from V11Y payload`() {
        val codec = InmotionI2WheelCodec()
        val data = ByteArray(74)
        data[0] = 0x10
        data[1] = 0x27 // 100.00 V core reading
        data[4] = 0xc4.toByte()
        data[5] = 0x09 // V11's offset would mean 25.00 km/h
        val frame = InmotionI2CommandBuilder.build(0x14, 0x04, data)

        val v11y = codec.newState() as InmotionI2WheelCodec.InmotionI2State
        v11y.carType = "V11Y"
        v11y.mainBoardMajor = 1
        v11y.mainBoardMinor = 3
        val yTelemetry = codec.decodeSafely(v11y, frame).filterIsInstance<DecodeEvent.TelemetryUpdate>().single().snapshot
        assertNull(yTelemetry.speedKmh)

        val v11 = codec.newState() as InmotionI2WheelCodec.InmotionI2State
        v11.carType = "V11"
        v11.mainBoardMajor = 1
        v11.mainBoardMinor = 3
        val v11Telemetry = codec.decodeSafely(v11, frame).filterIsInstance<DecodeEvent.TelemetryUpdate>().single().snapshot
        assertEquals(25f, v11Telemetry.speedKmh!!, 0.01f)
    }
}
