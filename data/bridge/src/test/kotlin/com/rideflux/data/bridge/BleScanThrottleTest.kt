/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScanThrottleTest {

    private var clock = 0L
    private fun throttle(maxStarts: Int = 4, window: Long = 30_000L) =
        BleScanThrottle(maxStarts = maxStarts, windowMillis = window, now = { clock })

    @Test
    fun `scans within the budget start immediately`() {
        val throttle = throttle()

        repeat(4) { assertEquals(0L, throttle.reserve()) }
    }

    @Test
    fun `the scan past the budget is held until the window rolls over`() {
        val throttle = throttle()
        repeat(4) { throttle.reserve() }

        assertEquals(30_000L, throttle.reserve())
    }

    @Test
    fun `budget frees up as the window slides`() {
        val throttle = throttle()
        repeat(4) { throttle.reserve() }

        // One millisecond after the first scan leaves the window there
        // is room again, and no artificial hold is imposed.
        clock = 30_001L

        assertEquals(0L, throttle.reserve())
    }

    @Test
    fun `each window admits exactly the budgeted number of scans`() {
        val throttle = throttle()

        repeat(4) { assertEquals(0L, throttle.reserve()) }
        // Reservations are booked at the instant they may run, not at
        // call time, so the next four share the rollover instant at
        // which the first window's scans age out...
        repeat(4) { assertEquals(30_000L, throttle.reserve()) }
        // ...and the ninth is pushed into the window after that rather
        // than piling onto the same one.
        assertEquals(60_000L, throttle.reserve())
    }

    @Test
    fun `a caller never gets a hold shorter than the one before it`() {
        val throttle = throttle()

        var previous = 0L
        repeat(12) {
            val hold = throttle.reserve()
            assertTrue("holds must be monotonic, got $hold after $previous", hold >= previous)
            previous = hold
        }
    }

    @Test
    fun `a long quiet period clears the history entirely`() {
        val throttle = throttle()
        repeat(4) { throttle.reserve() }

        clock = 120_000L

        repeat(4) { assertEquals(0L, throttle.reserve()) }
    }

    @Test
    fun `the shared instance leaves one slot below the platform limit`() {
        // Android mutes an app after five startScan calls in 30 s; the
        // spare slot is what stops the pairing scanner from starving the
        // reconnect loop.
        assertEquals(4, BleScanThrottle.MAX_STARTS_PER_WINDOW)
        assertEquals(30_000L, BleScanThrottle.WINDOW_MILLIS)
    }
}
