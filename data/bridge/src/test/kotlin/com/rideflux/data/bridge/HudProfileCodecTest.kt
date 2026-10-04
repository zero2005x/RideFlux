package com.rideflux.data.bridge

import com.rideflux.domain.settings.HudLayoutProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudProfileCodecTest {
    @Test fun `profile survives default ATT payload roundtrip`() {
        val expected = HudLayoutProfile(12, 8, 5, 9, -7, 4, 125, 93)
        val payload = HudProfileCodec.encode(expected)
        assertEquals(10, payload.size)
        assertEquals(expected, HudProfileCodec.decode(payload))
    }

    @Test fun `unknown version and truncated payload are ignored`() {
        val payload = HudProfileCodec.encode(HudLayoutProfile())
        assertNull(HudProfileCodec.decode(payload.copyOf(9)))
        payload[1] = 2
        assertNull(HudProfileCodec.decode(payload))
        payload[1] = 1
        payload[0] = 0
        assertNull(HudProfileCodec.decode(payload))
    }
}
