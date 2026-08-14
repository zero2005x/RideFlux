/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePeerFilterTest {
    @Test
    fun `allowlist matches only configured MAC ignoring case`() {
        val filter = BridgePeerFilter.Allowlist(setOf("AA:BB:CC:DD:EE:FF"))
        assertTrue(filter.acceptsAddress("aa:bb:cc:dd:ee:ff"))
        assertFalse(filter.acceptsAddress("11:22:33:44:55:66"))
        assertFalse(filter.acceptsAddress(null))
    }
}
