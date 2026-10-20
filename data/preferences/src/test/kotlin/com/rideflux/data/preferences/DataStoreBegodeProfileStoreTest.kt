/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rideflux.domain.wheel.BegodeProfileStore
import com.rideflux.domain.wheel.BegodeProtocolProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

class DataStoreBegodeProfileStoreTest {

    @Test
    fun dropsUnknownStoredNames() = runBlocking {
        val file = File.createTempFile("begode-profiles", ".preferences_pb")
        file.delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("profile_AA:BB:CC:DD:EE:FF")] = "NOT_A_PROFILE"
                preferences[stringPreferencesKey("profile_11:22:33:44:55:66")] = "A2_CAPTURED"
                preferences[stringPreferencesKey("unrelated")] = "LEGACY_DOCUMENTED"
            }
            val store = DataStoreBegodeProfileStore(dataStore, scope)
            store.awaitState(mapOf("11:22:33:44:55:66" to BegodeProtocolProfile.A2_CAPTURED))
            assertEquals(BegodeProtocolProfile.UNKNOWN, store.profileFor("aa:bb:cc:dd:ee:ff"))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.setProfile("  ", BegodeProtocolProfile.A2_CAPTURED) }
            }
            Unit
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class DataStoreBegodeProfileStoreRestartTest {

    @Test
    fun choiceSurvivesANewStoreAndUnknownRemovesIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val context = RuntimeEnvironment.getApplication()
            val store = DataStoreBegodeProfileStore(context, scope)
            store.setProfile(" aa:bb:cc:dd:ee:ff ", BegodeProtocolProfile.A2_CAPTURED)
            val saved = mapOf("AA:BB:CC:DD:EE:FF" to BegodeProtocolProfile.A2_CAPTURED)
            store.awaitState(saved)
            DataStoreBegodeProfileStore(context, scope).awaitState(saved)

            store.setProfile("AA:BB:CC:DD:EE:FF", BegodeProtocolProfile.UNKNOWN)
            store.awaitState(emptyMap())
            assertEquals(BegodeProtocolProfile.UNKNOWN, store.profileFor("AA:BB:CC:DD:EE:FF"))
        } finally {
            scope.cancel()
        }
    }
}

private suspend fun DataStoreBegodeProfileStore.awaitState(
    expected: Map<String, BegodeProtocolProfile>,
) = withTimeout(5_000) { profiles.first { it == expected } }
