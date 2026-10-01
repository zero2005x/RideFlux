/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ends this frame flow with an error when the link stays open but stops delivering, so that the
 * caller's retry loop scans and connects again.
 *
 * Android never reports this. The phone's app was killed and its new GATT server knows nothing
 * about the glasses, or the rider took the glasses off the approved list, or the approval window
 * ran out: in every case the connection stays up and the notifications simply stop, so a HUD that
 * only watches for a dropped link stays on "waiting for phone" until somebody restarts it.
 *
 * [unanswered] counts how many attempts in a row stayed open without ever delivering a frame. The
 * caller owns it, because it has to outlive the attempt this flow stands for. See
 * [LinkSilenceWatchdog] for the limits.
 */
fun Flow<BridgeFrame>.endWhenSilent(unanswered: AtomicInteger): Flow<BridgeFrame> = channelFlow {
    val watchdog = LinkSilenceWatchdog(unanswered)
    val supervisor = launch { throw IllegalStateException(watchdog.awaitSilence()) }
    try {
        collect { frame ->
            watchdog.onFrame()
            send(frame)
        }
    } finally {
        supervisor.cancel()
    }
}

/**
 * The clock behind [endWhenSilent]: how long a link may stay quiet before it is given up.
 *
 * The phone sends something once a second for as long as it is running (a standby heartbeat when
 * there is no wheel), so once the first frame has arrived a link that then stays quiet for
 * [SILENT_AFTER_STREAM_MILLIS] is treated as dead. That is far longer than the few seconds after
 * which the HUD marks its data stale, on purpose: a phone with its screen off holds no wake lock,
 * so its CPU can sleep between wheel notifications and the heartbeat is only as regular as the
 * phone is awake. The watchdog is there to replace a dead link, not to churn a live one.
 *
 * Before the first frame the flow also covers scanning, connecting and subscribing, which
 * [BridgeClient] bounds with watchdogs of its own, and for glasses that are not approved yet the
 * phone's approval window ([BridgeProtocol.PENDING_AUTHORIZATION_TIMEOUT_MILLIS]). The wait there
 * is that window plus [CONNECT_ALLOWANCE_MILLIS], so a request that lapsed is raised again, and it
 * doubles each time the last one went unanswered (up to [NO_FRAME_MAX_MILLIS]) so glasses that
 * were turned down do not keep asking.
 */
internal class LinkSilenceWatchdog(private val unanswered: AtomicInteger) {

    private val frames = AtomicInteger(0)

    /** Call for every frame the link delivers. */
    fun onFrame() {
        frames.incrementAndGet()
    }

    /** Suspends while the link is healthy and returns why it is not as soon as it is judged dead. */
    suspend fun awaitSilence(): String {
        val firstFrameLimit = firstFrameLimitMillis(unanswered.get())
        var silentMillis = 0L
        var streamed = false
        while (true) {
            delay(POLL_MILLIS)
            if (frames.getAndSet(0) > 0) {
                if (!streamed) unanswered.set(0)
                streamed = true
                silentMillis = 0L
                continue
            }
            silentMillis += POLL_MILLIS
            val limit = if (streamed) SILENT_AFTER_STREAM_MILLIS else firstFrameLimit
            if (silentMillis >= limit) return verdict(streamed, silentMillis)
        }
    }

    /** The reason the link is given up; a link that never answered is remembered for the next wait. */
    private fun verdict(streamed: Boolean, silentMillis: Long): String {
        if (streamed) return "bridge link silent for ${silentMillis}ms"
        unanswered.incrementAndGet()
        return "bridge delivered no frame in ${silentMillis}ms"
    }

    internal companion object {
        const val POLL_MILLIS = 1_000L

        /** A link that was streaming and then stayed quiet for this long is treated as dead. */
        const val SILENT_AFTER_STREAM_MILLIS = 30_000L

        /** What scanning, connecting and subscribing may take together, with room to spare. */
        const val CONNECT_ALLOWANCE_MILLIS = 40_000L

        /** First wait for a frame: the phone's approval window plus [CONNECT_ALLOWANCE_MILLIS]. */
        const val NO_FRAME_AT_START_MILLIS = BridgeProtocol.PENDING_AUTHORIZATION_TIMEOUT_MILLIS + CONNECT_ALLOWANCE_MILLIS

        /** The wait for a first frame never grows past this, however often it went unanswered. */
        const val NO_FRAME_MAX_MILLIS = 600_000L

        private const val MAX_BACKOFF_SHIFT = 4

        /** The wait for a first frame after [unanswered] unanswered attempts in a row. */
        fun firstFrameLimitMillis(unanswered: Int): Long =
            minOf(NO_FRAME_AT_START_MILLIS shl unanswered.coerceIn(0, MAX_BACKOFF_SHIFT), NO_FRAME_MAX_MILLIS)
    }
}
