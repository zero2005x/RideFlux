package com.rideflux.protocol.familybms.jbd

import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JbdCodecTest {
    @Test fun `H1 read vector and H2 probe differ`() {
        assertArrayEquals(hex("DD A5 03 00 FF FD 77"), JbdCodec.readRequest(3))
        assertArrayEquals(hex("DD A5 03 00 00 00 77"),
            JbdCodec.readRequest(3, JbdCodec.ChecksumHypothesis.H2))
    }

    @Test fun `vendor basic frame decodes physical values`() {
        val frame = hex("DD03001D0618000001F201F400002C7C00000000000080640304030B8B0B8A0B84FA8D77")
        val t = requireNotNull(JbdCodec.decode(frame, 42))
        assertEquals(15.6f, t.totalVoltageV!!, 0.01f)
        assertEquals(4.98f, t.remainingCapacityAh!!, 0.01f)
        assertEquals(3, t.temperatures.size)
        assertEquals(22.4f, t.temperatures[0].value, 0.01f)
        assertEquals(true, t.chargeMosEnabled)
        assertEquals(true, t.dischargeMosEnabled)
        assertNull(JbdCodec.decode(frame.copyOf(frame.size - 1), 42))
        val corrupt = frame.copyOf().apply { this[10] = (this[10].toInt() xor 1).toByte() }
        assertNull(JbdCodec.decode(corrupt, 42))
    }

    @Test fun `real cell frame is big endian millivolts`() {
        val t = requireNotNull(JbdCodec.decode(hex("DD0400080C790C790C740C7AFDE877"), 10))
        assertEquals(listOf(3193, 3193, 3188, 3194), t.cellVoltages.map { it.value })
    }
}
