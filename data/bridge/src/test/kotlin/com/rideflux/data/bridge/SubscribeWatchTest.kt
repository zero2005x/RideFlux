/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscribeWatchTest {
    private var now = 1_000L
    private val watch = SubscribeWatch(elapsedRealtime = { now })

    @Test
    fun aFreshAttemptIsNotOverdue() {
        watch.begin()
        assertFalse(watch.expired())
        assertNull(watch.describeExpiry())
    }

    @Test
    fun aLinkThatNeverComesUpGetsTheFullOverallBudget() {
        watch.begin()
        now += 9_999
        assertFalse(watch.expired())
        now += 1
        assertTrue(watch.expired())
        assertTrue(watch.describeExpiry()!!.contains("after the attempt began"))
    }

    @Test
    fun onceTheLinkIsUpAHungSequenceIsCaughtMuchSooner() {
        watch.begin()
        now += 2_000
        watch.linkUp()

        now += 3_999
        assertFalse("within the 4 s that the sequence normally needs under 1 s of", watch.expired())
        now += 1
        assertTrue(watch.expired())
    }

    @Test
    fun theTimeoutNamesTheLastStepThatWasReached() {
        watch.begin()
        watch.linkUp()
        watch.step("services discovered")
        watch.step("handshake written")
        now += 4_000

        val reason = watch.describeExpiry()!!

        assertTrue(reason, reason.contains("after the link came up"))
        assertTrue(reason, reason.endsWith("last step: handshake written"))
    }

    @Test
    fun aDroppedLinkStopsTheShortClockUntilItComesBack() {
        watch.begin()
        watch.linkUp()
        now += 3_000
        watch.linkDown()

        now += 4_000 // 7 s since begin(): well over the 4 s short limit, under the overall one.
        assertFalse(watch.expired())

        watch.linkUp()
        now += 2_999 // 9.999 s since begin(): the overall limit is still not reached.
        assertFalse(watch.expired())
        now += 1_001 // 4.000 s after the second linkUp(): now the short limit applies again.
        assertTrue(watch.expired())
    }

    @Test
    fun theOverallLimitStillAppliesAfterAReconnect() {
        watch.begin()
        now += 9_000
        watch.linkUp()
        now += 1_000
        assertTrue("overall budget spent even though the link has only been up 1 s", watch.expired())
        assertEquals(true, watch.describeExpiry()!!.contains("after the attempt began"))
    }

    @Test
    fun beginKeepsTheFirstStartTimeWhenCalledTwice() {
        watch.begin()
        now += 6_000
        watch.begin()
        now += 4_000
        assertTrue(watch.expired())
    }
}
