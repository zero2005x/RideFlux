/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.di

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.wheel.WheelBatteryPackStore
import com.rideflux.domain.wheel.WheelFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The battery-pack answer travels store -> Hilt module -> codec factory -> codec. The pieces are
 * unit-tested separately; these tests check the seams the DI modules own, in particular that an
 * address typed in a different case than the stored one still finds its answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BatteryPackWiringTest {

    /** A live frame reporting 66.88 V. */
    private val frame = intArrayOf(
        0x55, 0xAA, 0x1A, 0x20, 0, 0, 0, 0, 0, 0, 0x01, 0x2C, 0xFD, 0xCA, 0x00, 0x01,
        0xFF, 0xF8, 0x00, 0x18, 0x5A, 0x5A, 0x5A, 0x5A,
    ).map { it.toByte() }.toByteArray()

    private fun percentOf(codec: WheelCodec): Float? =
        codec.decode(codec.newState(), frame)
            .filterIsInstance<DecodeEvent.TelemetryUpdate>()
            .single().snapshot.batteryPercent

    private class FakePacks(initial: Map<String, Int>) : WheelBatteryPackStore {
        override val seriesCells = MutableStateFlow(initial)
        override suspend fun setSeriesCells(address: String, cells: Int?) = Unit
    }

    @Test
    fun theCodecFactoryLooksUpTheAnswerByNormalisedAddress() {
        val packs = FakePacks(mapOf(WheelBatteryPackStore.key("AA:BB:CC:DD:EE:FF") to 20))
        val factory = BleModule.provideWheelCodecFactoryImpl(packs)

        val answered = factory.forFamilyWithAddress(WheelFamily.G, " aa:bb:cc:dd:ee:ff")
        val other = factory.forFamilyWithAddress(WheelFamily.G, "11:22:33:44:55:66")

        assertEquals(6f, percentOf(answered))
        assertNull(percentOf(other))
        // The lookup happens per frame, so a later answer reaches the running codec.
        packs.seriesCells.value = packs.seriesCells.value + (WheelBatteryPackStore.key("11:22:33:44:55:66") to 16)
        assertEquals(100f, percentOf(other))
    }

    @Test
    fun theStoreStartsEmptyAndIsBackedByTheApplicationContext() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = PersistenceModule.provideWheelBatteryPackStore(RuntimeEnvironment.getApplication(), scope)

            assertTrue(store.seriesCells.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
