/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.di

import com.rideflux.domain.wheel.WheelBatteryPackStore
import com.rideflux.domain.wheel.WheelFamily
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.UUID

/**
 * The T02 detector reaches production only through the Hilt module, so the seam
 * is checked here: the application factory must still answer `G` for the owner's
 * A2 table *and* leave the tied signature ids in the diagnostic log.
 *
 * The A2 table is the captured one — one service `FFE0`, one notify+write
 * characteristic `FFE1` (`CAP-A-a2-live-capture.md:14`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GattSignatureWiringTest {

    private class FakePacks : WheelBatteryPackStore {
        override val seriesCells = MutableStateFlow(emptyMap<String, Int>())
        override suspend fun setSeriesCells(address: String, cells: Int?) = Unit
    }

    private val a2Table = mapOf(
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb") to
            listOf(UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")),
    )

    @Test
    fun theApplicationFactoryLogsTheTieAndStillResolvesTheA2ToFamilyG() {
        val factory = BleModule.provideWheelCodecFactoryImpl(FakePacks())
        ShadowLog.clear()

        // No name hint: the seeded signatures tie, which is the case a bug
        // report has to be able to explain.
        assertEquals(WheelFamily.G, factory.inferFromGattTable(a2Table, name = null))

        val logged = ShadowLog.getLogs()
            .filter { it.tag == "BleModule" }
            .joinToString("\n") { it.msg }
        assertTrue(
            "the tie and its candidates must reach the log, got: $logged",
            logged.contains("G.ffe0-ffe1") && logged.contains("K.ffe0-ffe1") && logged.contains("V.ffe0-ffe1"),
        )
    }

    @Test
    fun aDecidedWheelIsNotLoggedAsAFallback() {
        val factory = BleModule.provideWheelCodecFactoryImpl(FakePacks())
        ShadowLog.clear()

        // The name hint decides among the tied candidates, so nothing fell back.
        assertEquals(WheelFamily.K, factory.inferFromGattTable(a2Table, name = "KS-16X"))

        assertTrue(
            "a PROBABLE detection must not be reported as a fallback",
            ShadowLog.getLogs().none { it.tag == "BleModule" },
        )
    }
}
