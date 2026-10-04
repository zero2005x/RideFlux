package com.rideflux.app.bridge

import com.rideflux.data.bridge.HudProfileCodec
import com.rideflux.domain.settings.HudLayoutProfile

/** Sends a changed HUD profile promptly and refreshes it for a live CXR connection. */
internal class HudProfileSender(
    private val profileFor: (String) -> HudLayoutProfile,
    private val send: (ByteArray) -> Boolean,
    private val now: () -> Long,
) {
    private var lastPayload: ByteArray? = null
    private var lastSentAt = 0L

    fun reset() {
        lastPayload = null
        lastSentAt = 0L
    }

    fun sendIfNeeded(mac: String?) {
        if (mac == null) return
        val payload = HudProfileCodec.encode(profileFor(mac.replace(":", "")))
        val timestamp = now()
        if (lastPayload?.contentEquals(payload) == true && timestamp - lastSentAt < REFRESH_MILLIS) return
        if (send(payload)) {
            lastPayload = payload
            lastSentAt = timestamp
        }
    }

    private companion object {
        const val REFRESH_MILLIS = 5_000L
    }
}
