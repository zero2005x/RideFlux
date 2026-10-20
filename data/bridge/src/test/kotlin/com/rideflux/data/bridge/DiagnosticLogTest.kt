/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = 1_791_000_000_000L
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File(folder.root, "diagnostics")
    }

    @After fun tearDown() = DiagnosticLogs.install(null)

    private fun log(max: Long = 1_024) = DiagnosticLog(dir, clock = { now++ }, maxFileBytes = max)

    @Test fun `lines keep their order and carry tag and message`() {
        val log = log()
        log.record("bridge", "state STANDBY -> RELAYING")
        log.record("glasses", "connected **:**:**:**:D6:F9")

        val lines = log.snapshot().trimEnd().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("[bridge] state STANDBY -> RELAYING"))
        assertTrue(lines[1].endsWith("[glasses] connected **:**:**:**:D6:F9"))
    }

    @Test fun `an empty log has an empty snapshot`() {
        assertEquals("", log().snapshot())
    }

    @Test fun `identical consecutive lines collapse into one line and a repeat count`() {
        val log = log()
        log.record("bridge", "notify failed")
        repeat(500) { log.record("bridge", "notify failed") }
        log.record("bridge", "state DEGRADED")

        val lines = log.snapshot().trimEnd().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("previous line repeated 500 more time(s)"))
        assertTrue(lines[2].endsWith("state DEGRADED"))
    }

    @Test fun `a pending repeat count is flushed into the snapshot`() {
        val log = log()
        repeat(3) { log.record("bridge", "same") }
        assertTrue(log.snapshot().contains("repeated 2 more time(s)"))
    }

    @Test fun `the ring never grows past two files and drops the oldest lines`() {
        val log = log(max = 400)
        repeat(200) { log.record("bridge", "event number $it") }

        val total = dir.listFiles().orEmpty().sumOf { it.length() }
        assertTrue("on-disk size $total", total <= 2 * 400)
        assertEquals(2, dir.listFiles().orEmpty().size)
        val text = log.snapshot()
        assertTrue("newest line kept", text.contains("event number 199"))
        assertFalse("oldest line dropped", text.contains("event number 0\n"))
    }

    @Test fun `a new instance on the same directory continues the existing file`() {
        log().record("bridge", "before restart")
        val second = log()
        second.record("bridge", "after restart")

        val lines = second.snapshot().trimEnd().lines()
        assertTrue(lines[0].endsWith("before restart"))
        assertTrue(lines[1].endsWith("after restart"))
    }

    @Test fun `newlines and control characters cannot forge extra log lines`() {
        val log = log()
        log.record("bridge", "first\nFORGED [bridge] state RELAYING\r\u0000")

        assertEquals(1, log.snapshot().trimEnd().lines().size)
    }

    @Test fun `very long messages are cut`() {
        val log = log(max = 10_000)
        log.record("bridge", "x".repeat(5_000))
        assertTrue(log.snapshot().length < 400)
    }

    @Test fun `clear removes everything`() {
        val log = log()
        log.record("bridge", "something")
        log.clear()
        assertEquals("", log.snapshot())
        log.record("bridge", "fresh")
        assertEquals(1, log.snapshot().trimEnd().lines().size)
    }

    @Test fun `an unwritable directory never throws`() {
        val blocker = folder.newFile("not-a-directory")
        val log = DiagnosticLog(File(blocker, "sub"))
        log.record("bridge", "dropped silently")
        assertEquals("", log.snapshot())
    }

    @Test fun `the holder is a no-op until a log is installed`() {
        DiagnosticLogs.record("bridge", "nobody listening")
        assertEquals("", DiagnosticLogs.snapshot())

        DiagnosticLogs.install(log())
        DiagnosticLogs.record("bridge", "now recorded")
        assertTrue(DiagnosticLogs.snapshot().contains("now recorded"))
    }

    @Test fun `addresses are masked to the last two octets`() {
        assertEquals("**:**:**:**:D6:F9", DiagnosticLogs.maskAddress("ac:86:d1:55:d6:f9"))
        assertEquals("?", DiagnosticLogs.maskAddress(null))
        assertEquals("?", DiagnosticLogs.maskAddress("not an address"))
    }
}
