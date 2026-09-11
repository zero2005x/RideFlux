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

    @Test
    fun `classifies advertised names using table-driven patterns`() {
        data class Case(val name: String?, val expected: WheelFamily?)
        val cases = listOf(
            // Inmotion legacy (I1) and modern (I2).
            Case("V5F-2A4AC0", WheelFamily.I1),
            Case("INMOTION V8F", WheelFamily.I1),
            Case("V10F", WheelFamily.I1),
            Case("V11Y", WheelFamily.I2),
            Case("V12-ABCDEF", WheelFamily.I2),
            Case("Inmotion_V14", WheelFamily.I2),
            // KingSong.
            Case("KS-16X", WheelFamily.K),
            Case("KSS18", WheelFamily.K),
            Case("KS-S18", WheelFamily.K),
            Case("KS-F22P", WheelFamily.K),
            Case("RW", WheelFamily.K),
            Case("ROCKWHEEL", WheelFamily.K),
            // Veteran / Leaperkim.
            Case("Sherman-S", WheelFamily.V),
            Case("VETERAN ABRAMS", WheelFamily.V),
            Case("LEAPERKIM PATTON", WheelFamily.V),
            Case("LK-EX", WheelFamily.V),
            // Ninebot.
            Case("NBZ", WheelFamily.N2),
            Case("Ninebot Z10", WheelFamily.N2),
            Case("NINEBOT ZT", WheelFamily.N2),
            Case("NB-E+", WheelFamily.N1),
            Case("NINEBOT S2", WheelFamily.N1),
            // Begode / Gotway / ExtremeBull.
            Case("GotWay_Master", WheelFamily.G),
            Case("Begode EX30", WheelFamily.G),
            Case("ExtremeBull", WheelFamily.G),
            Case("GW-RS", WheelFamily.G),
            Case("EXN", WheelFamily.G),
            Case("MSP", WheelFamily.G),
            Case("TESLA", WheelFamily.G),
            // Non-matches.
            Case(null, null),
            Case("", null),
            Case("   ", null),
            Case("AirPods Pro", null),
            Case("V7", null),
            Case("V1", null),
        )

        cases.forEach { case ->
            assertEquals("name=${case.name}", case.expected, WheelNameClassifier.classify(case.name))
        }
    }

    @Test
    fun `veteran names keep precedence over begode model fragments`() {
        assertEquals(WheelFamily.V, WheelNameClassifier.classify("Veteran RS"))
        assertEquals(WheelFamily.V, WheelNameClassifier.classify("Patton"))
    }

    @Test
    fun `does not confuse malformed inmotion prefixes`() {
        assertNull(WheelNameClassifier.classify("V101"))
        assertNull(WheelNameClassifier.classify("V123456"))
    }
}
