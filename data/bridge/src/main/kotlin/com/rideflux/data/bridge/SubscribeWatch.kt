/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Decides when one HUD connection attempt has taken too long to reach "subscribed".
 *
 * Two limits apply. [overallMillis] covers the whole attempt from the moment the scan result was
 * acted on, and has to stay generous because setting up a BLE link at -95 dBm can take seconds.
 * [afterLinkMillis] starts when the link is up: from there the rest of the sequence (MTU, service
 * discovery, handshake write, CCCD write) normally finishes in well under a second, so a link
 * that has said nothing for this long is hung, not slow. Waiting out the overall limit for it
 * is what made every second reconnect on 2026-10-08 take ten seconds (22:23:56 and 22:29:19).
 *
 * The last step reached is kept so the timeout can say where the sequence stopped.
 */
internal class SubscribeWatch(
    private val elapsedRealtime: () -> Long,
    private val overallMillis: Long = OVERALL_MILLIS,
    private val afterLinkMillis: Long = AFTER_LINK_MILLIS,
) {
    private val startedAt = AtomicLong(NOT_SET)
    private val linkUpAt = AtomicLong(NOT_SET)
    private val lastStep = AtomicReference("waiting for the link")

    /** The attempt begins: starts the overall clock. */
    fun begin() {
        startedAt.compareAndSet(NOT_SET, elapsedRealtime())
    }

    /** The GATT link is up. A reconnect after a status-133 drop calls [linkDown] first. */
    fun linkUp() {
        linkUpAt.set(elapsedRealtime())
        lastStep.set("link up")
    }

    fun linkDown() {
        linkUpAt.set(NOT_SET)
        lastStep.set("link down")
    }

    fun step(name: String) {
        lastStep.set(name)
    }

    fun expired(): Boolean = describeExpiry() != null

    /** Why the attempt is overdue, or null while it is still within both limits. */
    fun describeExpiry(): String? {
        val now = elapsedRealtime()
        val up = linkUpAt.get()
        if (up != NOT_SET && now - up >= afterLinkMillis) {
            return "no subscription ${now - up} ms after the link came up; last step: ${lastStep.get()}"
        }
        val start = startedAt.get()
        if (start != NOT_SET && now - start >= overallMillis) {
            return "no subscription ${now - start} ms after the attempt began; last step: ${lastStep.get()}"
        }
        return null
    }

    companion object {
        const val OVERALL_MILLIS = 10_000L
        const val AFTER_LINK_MILLIS = 4_000L
        private const val NOT_SET = -1L
    }
}
