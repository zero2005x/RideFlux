package com.rideflux.app.bridge

import com.rideflux.data.bridge.HudProfileCodec
import com.rideflux.domain.settings.HudLayoutProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudProfileSenderTest {
    @Test
    fun sendsOnConnectAndChangeAndRefreshesAnUnchangedProfile() {
        var time = 100L
        var profile = HudLayoutProfile(leftInset = 8)
        val ids = mutableListOf<String>()
        val sent = mutableListOf<ByteArray>()
        val sender = HudProfileSender(
            profileFor = { id -> ids += id; profile },
            send = { sent += it; true },
            now = { time },
        )

        sender.sendIfNeeded(null)
        assertTrue(sent.isEmpty())
        sender.sendIfNeeded("AA:BB:CC:DD:EE:FF")
        assertEquals(listOf("AABBCCDDEEFF"), ids)
        assertEquals(profile, HudProfileCodec.decode(sent.single()))

        time += 1_000
        sender.sendIfNeeded("AA:BB:CC:DD:EE:FF")
        assertEquals(1, sent.size)

        profile = HudLayoutProfile(fontPercent = 130)
        sender.sendIfNeeded("AA:BB:CC:DD:EE:FF")
        assertEquals(profile, HudProfileCodec.decode(sent.last()))
        assertEquals(2, sent.size)

        time += 5_000
        sender.sendIfNeeded("AA:BB:CC:DD:EE:FF")
        assertEquals(3, sent.size)

        sender.reset()
        sender.sendIfNeeded("AA:BB:CC:DD:EE:FF")
        assertEquals(4, sent.size)
    }

    @Test
    fun failedSendIsRetriedOnTheNextPoll() {
        var attempts = 0
        val sender = HudProfileSender(
            profileFor = { HudLayoutProfile() },
            send = { attempts++; attempts > 1 },
            now = { 1_000L },
        )

        sender.sendIfNeeded("112233445566")
        sender.sendIfNeeded("112233445566")
        sender.sendIfNeeded("112233445566")
        assertEquals(2, attempts)
    }
}
