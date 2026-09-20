/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The defaults here are the ones the README advertises and the ones a
 * first-run rider gets before touching Settings, so they are worth
 * pinning: a silent change to any of them changes when the app warns
 * about an overheating board or a flat pack.
 */
class AppSettingsTest {

    @Test
    fun `alert threshold defaults match the documented safety limits`() {
        val thresholds = AlertThresholds()
        assertEquals(45f, thresholds.speedLimitKmh)
        assertEquals(80f, thresholds.temperatureLimitC)
        assertEquals(25f, thresholds.lowBatteryPercent)
        assertEquals(90f, thresholds.pwmAlertPercent)
    }

    @Test
    fun `alerts are on by default`() {
        assertTrue(AlertThresholds().enabled)
    }

    @Test
    fun `app defaults are metric, awake, and bridge-autostarting`() {
        val settings = AppSettings()
        assertTrue(settings.useMetric)
        assertTrue(settings.keepScreenOnDashboard)
        assertTrue(settings.bridgeAutostart)
    }

    @Test
    fun `low-latency standby is off by default because it costs battery`() {
        assertFalse(AppSettings().bridgeStandbyAdvertiseLowLatency)
    }

    @Test
    fun `no HUD peer is paired until the rider pairs one`() {
        assertNull(AppSettings().hudPeerMac)
    }

    @Test
    fun `hardware assumption defaults are unconfigured or unmirrored`() {
        val settings = AppSettings()
        assertNull(settings.ringKeyCode)
        assertFalse(settings.hudMirrorHorizontally)
        assertNull(settings.preferredGlassesMac)
    }

    @Test
    fun `copy leaves untouched settings alone`() {
        val settings = AppSettings()
        val updated = settings.copy(
            useMetric = false,
            ringKeyCode = 24,
            hudMirrorHorizontally = true,
            preferredGlassesMac = "11:22:33:44:55:66",
        )
        assertFalse(updated.useMetric)
        assertEquals(settings.alertThresholds, updated.alertThresholds)
        assertEquals(settings.bridgeAutostart, updated.bridgeAutostart)
        assertEquals(settings.hudPeerMac, updated.hudPeerMac)
        assertEquals(24, updated.ringKeyCode)
        assertTrue(updated.hudMirrorHorizontally)
        assertEquals("11:22:33:44:55:66", updated.preferredGlassesMac)
    }

    @Test
    fun `thresholds compare by value so a no-op save is detectable`() {
        assertEquals(AlertThresholds(), AlertThresholds())
        assertEquals(AppSettings(), AppSettings())
    }
}
