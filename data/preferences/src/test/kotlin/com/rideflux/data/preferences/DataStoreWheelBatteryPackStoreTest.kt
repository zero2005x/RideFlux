/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.preferences

import com.rideflux.domain.wheel.WheelBatteryPackStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DataStoreWheelBatteryPackStoreTest {

    private suspend fun DataStoreWheelBatteryPackStore.awaitState(expected: Map<String, Int>) =
        withTimeout(5_000) { seriesCells.first { it == expected } }

    @Test
    fun answersBelongToOneWheelSurviveRestartAndRejectNonsense() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val context = RuntimeEnvironment.getApplication()
            val store = DataStoreWheelBatteryPackStore(context, scope)
            assertTrue(store.seriesCells.value.isEmpty())

            store.setSeriesCells(" aa:bb:cc:dd:ee:ff ", 20)
            store.setSeriesCells("11:22:33:44:55:66", 16)
            val both = mapOf("AA:BB:CC:DD:EE:FF" to 20, "11:22:33:44:55:66" to 16)
            store.awaitState(both)
            assertEquals(20, store.seriesCells.value[WheelBatteryPackStore.key("aa:bb:cc:dd:ee:ff")])

            // A second instance over the same file sees the same answers.
            DataStoreWheelBatteryPackStore(context, scope).awaitState(both)

            // Another answer replaces the first; forgetting removes only that wheel.
            store.setSeriesCells("AA:BB:CC:DD:EE:FF", 24)
            store.awaitState(mapOf("AA:BB:CC:DD:EE:FF" to 24, "11:22:33:44:55:66" to 16))
            store.setSeriesCells("11:22:33:44:55:66", null)
            store.awaitState(mapOf("AA:BB:CC:DD:EE:FF" to 24))

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.setSeriesCells("  ", 20) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.setSeriesCells("AA:BB:CC:DD:EE:FF", 17) }
            }
            // A rejected write changes nothing.
            assertEquals(mapOf("AA:BB:CC:DD:EE:FF" to 24), store.seriesCells.value)
        } finally {
            scope.cancel()
        }
    }
}
