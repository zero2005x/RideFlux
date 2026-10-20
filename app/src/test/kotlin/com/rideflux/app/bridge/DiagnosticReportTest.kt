/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val now = Instant.parse("2026-10-08T05:00:00Z")

    private fun report(log: String) = DiagnosticReport.build("0.1.11 (12)", "13", "Xiaomi 21091116UG", now, zone, log)

    @Test
    fun headerNamesTheBuildAndPhoneAndStatesTheTimeZone() {
        val lines = report("12:50:13 [bridge] x\n").lines()
        assertEquals("RideFlux diagnostic log", lines[0])
        assertEquals("App: 0.1.11 (12)", lines[1])
        assertEquals("Android: 13", lines[2])
        assertEquals("Device: Xiaomi 21091116UG", lines[3])
        assertEquals("Saved: 2026-10-08T13:00:00+08:00", lines[4])
        assertTrue(lines[5].contains("Asia/Taipei"))
        assertEquals("----", lines[6])
    }

    @Test
    fun theLogIsAppendedUnchanged() {
        val log = "2026-10-08 12:50:13.123 [bridge] state RELAYING -> DEGRADED\n"
        assertTrue(report(log).endsWith("----\n$log"))
    }

    @Test
    fun anEmptyLogSaysSoInsteadOfProducingAnEmptyFile() {
        assertTrue(report("").endsWith("----\n(no events recorded yet)\n"))
    }
}
