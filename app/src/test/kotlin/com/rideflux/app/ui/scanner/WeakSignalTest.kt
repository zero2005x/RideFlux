/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeakSignalTest {

    @Test
    fun aDistantWheel_isFlaggedBeforeTheRiderTapsIt() {
        // -94 dBm is what a wheel parked across a car park actually
        // reported during bring-up; connecting at that level fails or
        // drops mid-handshake.
        assertTrue(isWeakSignal(-94))
        assertTrue(isWeakSignal(WEAK_RSSI_DBM))
    }

    @Test
    fun aNearbyWheel_isNotFlagged() {
        assertFalse(isWeakSignal(-62))
        assertFalse(isWeakSignal(WEAK_RSSI_DBM + 1))
    }

    @Test
    fun anAbsentReading_isNotTreatedAsWeak() {
        // No RSSI is not a bad RSSI; warning on it would put a red
        // label on every device whose advertisement omitted one.
        assertFalse(isWeakSignal(null))
    }
}
