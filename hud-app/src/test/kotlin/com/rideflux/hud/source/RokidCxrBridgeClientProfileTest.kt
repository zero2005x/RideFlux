package com.rideflux.hud.source

import com.rideflux.data.bridge.HudProfileCodec
import com.rideflux.domain.settings.HudLayoutProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class RokidCxrBridgeClientProfileTest {
    @Test
    fun onlyValidProfilePayloadsReachTheListener() {
        val received = mutableListOf<HudLayoutProfile>()
        RokidCxrBridgeClient.setProfileListener(received::add)
        try {
            RokidCxrBridgeClient.acceptProfilePayload(null)
            RokidCxrBridgeClient.acceptProfilePayload(byteArrayOf(1, 2))
            val profile = HudLayoutProfile(leftInset = 12, visibleItems = HudLayoutProfile.CLOCK)
            RokidCxrBridgeClient.acceptProfilePayload(HudProfileCodec.encode(profile))
            assertEquals(listOf(profile), received)
        } finally {
            RokidCxrBridgeClient.setProfileListener {}
        }
    }
}
