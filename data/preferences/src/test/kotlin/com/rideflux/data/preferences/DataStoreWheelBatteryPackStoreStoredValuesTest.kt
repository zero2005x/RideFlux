/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Feeds [DataStoreWheelBatteryPackStore] stored contents it did not write itself (an older or
 * damaged file), through an in-memory [DataStore].
 */
class DataStoreWheelBatteryPackStoreStoredValuesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> })

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class FixedStore(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private class FailingStore(private val error: Throwable) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw error
    }

    @Test
    fun onlySupportedPackSizesUnderTheWheelPrefixAreTrusted() {
        val store = DataStoreWheelBatteryPackStore(
            FixedStore(
                mutablePreferencesOf(
                    intPreferencesKey("cells_AA:BB:CC:DD:EE:FF") to 20,
                    intPreferencesKey("cells_11:22:33:44:55:66") to 17, // not a pack size
                    intPreferencesKey("cells_77:88:99:AA:BB:CC") to 0,
                    intPreferencesKey("cell_count") to 20, // someone else's key
                    stringPreferencesKey("cells_DD:EE:FF:00:11:22") to "20", // wrong type
                ),
            ),
            scope,
        )

        assertEquals(mapOf("AA:BB:CC:DD:EE:FF" to 20), store.seriesCells.value)
    }

    @Test
    fun anUnreadableStoreMeansNoAnswersYet() {
        for (failure in listOf(IOException("disk"), CorruptionException("bad bytes"))) {
            val store = DataStoreWheelBatteryPackStore(FailingStore(failure), scope)

            assertTrue(store.seriesCells.value.isEmpty())
        }
    }

    @Test
    fun aFailureThatIsNotAReadErrorLeavesTheLastKnownAnswers() {
        val store = DataStoreWheelBatteryPackStore(FailingStore(IllegalStateException("bug")), scope)

        // Not swallowed as a read error: the collecting coroutine fails (the handler above absorbs
        // it), and the state simply stays at its initial empty value instead of inventing data.
        assertTrue(store.seriesCells.value.isEmpty())
    }
}
