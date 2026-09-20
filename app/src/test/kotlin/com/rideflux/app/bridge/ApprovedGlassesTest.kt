/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedGlassesTest {

    @Test
    fun approvedGlasses_propertiesAndLegacyCheck() {
        val modern = ApprovedGlasses(
            tokenHex = "0102030405060708",
            mac = "AA:BB:CC:DD:EE:FF",
            shortCode = "0102",
        )
        assertFalse(modern.isLegacy)
        assertEquals("0102030405060708", modern.tokenHex)
        assertEquals("AA:BB:CC:DD:EE:FF", modern.mac)
        assertEquals("0102", modern.shortCode)

        val legacy = ApprovedGlasses(
            tokenHex = null,
            mac = "11:22:33:44:55:66",
            shortCode = "5566",
        )
        assertTrue(legacy.isLegacy)
        assertEquals("11:22:33:44:55:66", legacy.mac)
        assertEquals("5566", legacy.shortCode)
    }

    @Test
    fun glassesAuthorizationRequest_equalityAndHashCode() {
        val tokenA = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val tokenB = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val tokenC = byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1)

        val req1 = GlassesAuthorizationRequest("AA:BB:CC:DD:EE:FF", tokenA, "0102", false)
        val req2 = GlassesAuthorizationRequest("AA:BB:CC:DD:EE:FF", tokenB, "0102", false)
        val req3 = GlassesAuthorizationRequest("AA:BB:CC:DD:EE:FF", tokenC, "0807", false)
        val reqLegacy = GlassesAuthorizationRequest("AA:BB:CC:DD:EE:FF", null, "EEFF", true)

        assertEquals(req1, req2)
        assertEquals(req1.hashCode(), req2.hashCode())
        assertNotEquals(req1, req3)
        assertNotEquals(req1, reqLegacy)
    }
}
