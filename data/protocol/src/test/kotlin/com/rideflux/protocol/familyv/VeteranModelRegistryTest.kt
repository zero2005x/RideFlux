/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of RideFlux. It is licensed under the GNU General
 * Public License v3.0-or-later; see the LICENSE file for the full text.
 */

package com.rideflux.protocol.familyv

import com.rideflux.protocol.familyv.VeteranModelRegistry.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [VeteranModelRegistry].
 */
class VeteranModelRegistryTest {

    @Test
    fun `both source tables are represented`() {
        val live = VeteranModelRegistry.models.filter { it.source == Source.NOSFET_LIVE }
        val embedded = VeteranModelRegistry.models.filter { it.source == Source.LEAPERKIM_EMBEDDED }
        assertEquals("the live NOSFET endpoint serves two models", 2, live.size)
        assertEquals("the embedded LeaperKim table carries ten entries", 10, embedded.size)
        assertEquals(
            listOf("AERO", "APEX"),
            live.map { it.name }.sorted(),
        )
    }

    @Test
    fun `the four-character hardware code is the key, and three characters never matches`() {
        assertNotNull(VeteranModelRegistry.lookup("5010"))
        assertNotNull(VeteranModelRegistry.lookup("5020"))
        assertNull("a three-character code must not match", VeteranModelRegistry.lookup("501"))
        assertNull(VeteranModelRegistry.lookup("502"))
        assertEquals("APEX", VeteranModelRegistry.lookup("5010")?.name)
        assertEquals("AERO", VeteranModelRegistry.lookup("5020")?.name)
    }

    @Test
    fun `modernHardwareKey parses byte triples correctly`() {
        // e.g. raw 501000 = 0x07A508 -> byte30=0x07, byte28=0xA5, byte29=0x08
        val keyApex = VeteranModelRegistry.modernHardwareKey(0x07, 0xA5, 0x08)
        assertEquals("5010", keyApex)

        // Aero 502012 = 0x07A8FC -> byte30=0x07, byte28=0xA8, byte29=0xFC
        val keyAero = VeteranModelRegistry.modernHardwareKey(0x07, 0xA8, 0xFC)
        assertEquals("5020", keyAero)
    }

    @Test
    fun `the hardware codes match the vendor tables exactly`() {
        val byCode = VeteranModelRegistry.models.associateBy { it.hardwareCode }
        for (code in listOf("5010", "5020", "0070", "0060", "0050", "0040",
                            "0030", "0011", "0020", "0010", "6660", "6661")) {
            assertNotNull("code $code must be present", byCode[code])
        }
        assertEquals("APEX", byCode["5010"]?.name)
        assertEquals("AERO", byCode["5020"]?.name)
        assertEquals("Patton-S", byCode["0070"]?.name)
        assertEquals("Sherman-L", byCode["0060"]?.name)
        assertEquals("LYNX", byCode["0050"]?.name)
        assertEquals("Patton", byCode["0040"]?.name)
        assertEquals("Sherman-s", byCode["0030"]?.name)
        assertEquals("ShermanMax", byCode["0011"]?.name)
        assertEquals("Abrams", byCode["0020"]?.name)
        assertEquals("Sherman", byCode["0010"]?.name)
    }

    @Test
    fun `an unknown or malformed code resolves to null, never to a curve`() {
        assertNull(VeteranModelRegistry.lookup(null))
        assertNull(VeteranModelRegistry.lookup(""))
        assertNull(VeteranModelRegistry.lookup("   "))
        assertNull(VeteranModelRegistry.lookup("9999"))
        assertNull(VeteranModelRegistry.lookup("KS-18L"))
        assertNull(VeteranModelRegistry.lookup("50100"))
    }

    @Test
    fun `SysVol divided by four point two volts is an exact integer cell count`() {
        for (m in VeteranModelRegistry.models) {
            val quotient = m.systemVoltageVolts / VeteranModelRegistry.NOMINAL_FULL_CELL_VOLTS
            assertEquals(
                "${m.name}: SysVol ${m.systemVoltageVolts} / 4.20 should be a whole number",
                m.cells.toDouble(),
                quotient,
                1e-9,
            )
        }
    }

    @Test
    fun `the cell counts are the expected series configurations`() {
        fun cells(code: String) = VeteranModelRegistry.lookup(code)?.cells
        assertEquals(36, cells("5010"))   // APEX   151.2 V
        assertEquals(30, cells("5020"))   // AERO   126 V
        assertEquals(30, cells("0070"))   // Patton-S
        assertEquals(36, cells("0060"))   // Sherman-L
        assertEquals(36, cells("0050"))   // LYNX
        assertEquals(30, cells("0040"))   // Patton
        assertEquals(24, cells("0030"))   // Sherman-s  100.8 V
        assertEquals(24, cells("0011"))   // ShermanMax
        assertEquals(24, cells("0020"))   // Abrams
        assertEquals(24, cells("0010"))   // Sherman
    }

    @Test
    fun `the per-cell curve has 100 points spanning 315 to 412 centivolts`() {
        val c = VeteranModelRegistry.perCellCurveCentiVolts
        assertEquals(100, c.size)
        assertEquals(315, c.first())
        assertEquals(412, c.last())
        for (i in 1 until c.size) {
            assertTrue("point $i decreases: ${c[i - 1]} -> ${c[i]}", c[i] >= c[i - 1])
        }
        val early = c[1] - c[0]
        val late = c[99] - c[98]
        assertTrue("the curve should flatten near full charge", early > late)
    }

    @Test
    fun `every model carries the vendor's exact curve, not a reconstruction`() {
        val expectedSpans = mapOf(
            "5010" to (11340 to 14850),  // APEX
            "5020" to (9450 to 12375),   // AERO
            "0070" to (9450 to 12375),   // Patton-S
            "0060" to (11340 to 14850),  // Sherman-L
            "0050" to (11340 to 14850),  // LYNX
            "0040" to (9450 to 12375),   // Patton
            "0030" to (7560 to 9900),    // Sherman-s
            "0011" to (7560 to 9900),    // ShermanMax
            "0020" to (7560 to 9900),    // Abrams
            "0010" to (7560 to 9900),    // Sherman
        )
        for ((code, span) in expectedSpans) {
            val m = requireNotNull(VeteranModelRegistry.lookup(code)) { code }
            val curve = VeteranModelRegistry.curveCentiVolts(m)
            assertEquals("$code point count", 100, curve.size)
            assertEquals("$code 0% endpoint", span.first, curve.first())
            assertEquals("$code 100% endpoint", span.second, curve.last())
            for (i in 1 until curve.size) {
                assertTrue("$code point $i decreases", curve[i] >= curve[i - 1])
            }
            val recon = VeteranModelRegistry.perCellCurveCentiVolts.map { it * m.cells }
            assertNotEquals("$code: the reconstruction must not be mistaken for the table",
                curve.toList(), recon)
        }
    }

    @Test
    fun `state of charge follows the vendor's bracketing rule`() {
        val apex = requireNotNull(VeteranModelRegistry.lookup("5010"))
        val curve = VeteranModelRegistry.curveCentiVolts(apex)
        assertEquals(0, VeteranModelRegistry.stateOfChargePercent(apex, 0))
        assertEquals(0, VeteranModelRegistry.stateOfChargePercent(apex, curve[0] - 1))
        assertEquals(0, VeteranModelRegistry.stateOfChargePercent(apex, curve[0]))
        assertEquals(100, VeteranModelRegistry.stateOfChargePercent(apex, curve[99]))
        assertEquals(100, VeteranModelRegistry.stateOfChargePercent(apex, curve[99] + 5000))
        for (v in curve.first() until curve.last()) {
            val expected = curve.indexOfFirst { it >= v }
            val reported = requireNotNull(VeteranModelRegistry.stateOfChargePercent(apex, v))
            assertEquals("voltage $v", expected, reported)
        }
    }

    @Test
    fun `unified stateOfCharge handles known models, fallback seriesCells and unknown nulls`() {
        // Known Apex 5010: uses exact table
        val socKnown = VeteranModelRegistry.stateOfCharge("5010", 14850)
        assertEquals(100, socKnown)

        // Unknown model with userSeriesCells provided (e.g. Aeon 36S)
        val socFallback = VeteranModelRegistry.stateOfCharge("9999", 14850, userSeriesCells = 36)
        assertEquals(100, socFallback)

        // Unknown model without userSeriesCells returns null
        val socUnknown = VeteranModelRegistry.stateOfCharge("9999", 14850, userSeriesCells = null)
        assertNull(socUnknown)
    }

    @Test
    fun `a firmware sentinel has no state of charge`() {
        for (code in listOf("6660", "6661")) {
            val m = requireNotNull(VeteranModelRegistry.lookup(code))
            assertTrue("$code is a sentinel", m.isFirmwareSentinel)
            assertNull(
                "$code must not report a state of charge",
                VeteranModelRegistry.stateOfChargePercent(m, 400),
            )
        }
        assertFalse(VeteranModelRegistry.vehicles.any { it.isFirmwareSentinel })
        assertEquals(10, VeteranModelRegistry.vehicles.size)
    }
}
