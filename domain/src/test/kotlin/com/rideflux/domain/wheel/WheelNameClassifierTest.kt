/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.wheel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WheelNameClassifierTest {

    // ---- Inmotion legacy (the case that broke scanning) -----------------

    @Test
    fun `classifies the V5F advertised name that made the wheel undiscoverable`() {
        // Field report: an Inmotion V5F advertises "V5F-2A4AC0" and no
        // service UUID at all, so it never reached the device list.
        assertEquals(WheelFamily.I1, WheelNameClassifier.classify("V5F-2A4AC0"))
    }

    @Test
    fun `classifies every legacy Inmotion model line as I1`() {
        listOf("V5", "V5F", "V5F+", "V5F-2A4AC0", "V8", "V8F-1234AB", "V10", "V10F")
            .forEach { name ->
                assertEquals("name=$name", WheelFamily.I1, WheelNameClassifier.classify(name))
            }
    }

    @Test
    fun `classifies current Inmotion model lines as I2`() {
        listOf("V9", "V11", "V12-ABCDEF", "V13", "V14")
            .forEach { name ->
                assertEquals("name=$name", WheelFamily.I2, WheelNameClassifier.classify(name))
            }
    }

    @Test
    fun `accepts the INMOTION vendor prefix and is case-insensitive`() {
        assertEquals(WheelFamily.I1, WheelNameClassifier.classify("INMOTION V8F"))
        assertEquals(WheelFamily.I1, WheelNameClassifier.classify("inmotion-v5f-2a4ac0"))
        assertEquals(WheelFamily.I2, WheelNameClassifier.classify("Inmotion_V11"))
    }

    @Test
    fun `does not confuse V10 with V1 or V11 with V1`() {
        // The regression this pins: a greedy model match would read
        // "V101…" as model 10 and route a non-Inmotion device through
        // the legacy decoder.
        assertNull(WheelNameClassifier.classify("V101"))
        assertNull(WheelNameClassifier.classify("V1"))
        assertNull(WheelNameClassifier.classify("V123456"))
    }

    @Test
    fun `returns null for an unknown Inmotion model number`() {
        assertNull(WheelNameClassifier.classify("V7"))
    }

    // ---- Other families -------------------------------------------------

    @Test
    fun `classifies KingSong names`() {
        assertEquals(WheelFamily.K, WheelNameClassifier.classify("KS-16X"))
        assertEquals(WheelFamily.K, WheelNameClassifier.classify("KS18L"))
        assertEquals(WheelFamily.K, WheelNameClassifier.classify("KingSong 3000"))
    }

    @Test
    fun `classifies unambiguous Veteran model names`() {
        assertEquals(WheelFamily.V, WheelNameClassifier.classify("Sherman-S"))
        assertEquals(WheelFamily.V, WheelNameClassifier.classify("VETERAN ABRAMS"))
        assertEquals(WheelFamily.V, WheelNameClassifier.classify("Patton"))
    }

    @Test
    fun `splits Ninebot into the Z line and everything else`() {
        assertEquals(WheelFamily.N2, WheelNameClassifier.classify("Ninebot Z10"))
        assertEquals(WheelFamily.N1, WheelNameClassifier.classify("Ninebot E+"))
        assertEquals(WheelFamily.N1, WheelNameClassifier.classify("NINEBOT S2"))
    }

    @Test
    fun `classifies Begode vendor names`() {
        assertEquals(WheelFamily.G, WheelNameClassifier.classify("GotWay_Master"))
        assertEquals(WheelFamily.G, WheelNameClassifier.classify("Begode EX30"))
    }

    // ---- Non-matches fall through to UUID inference ----------------------

    @Test
    fun `returns null rather than guessing for unrelated devices`() {
        listOf(null, "", "   ", "AirPods Pro", "Mi Band 7", "Rokid RV101")
            .forEach { name ->
                assertNull("name=$name", WheelNameClassifier.classify(name))
            }
    }
}
