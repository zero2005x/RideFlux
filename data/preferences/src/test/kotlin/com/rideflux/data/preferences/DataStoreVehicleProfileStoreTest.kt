/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rideflux.domain.device.PlevCategory
import com.rideflux.domain.garage.VehicleCalibration
import com.rideflux.domain.garage.vehicleProfile
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentityEvidence
import com.rideflux.domain.garage.updateIdentifiedIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Persistence and isolation contract for the saved-vehicle garage.
 *
 * The restart case builds a second DataStore/store pair over the same file,
 * which is the closest JVM equivalent of recreating the repository after a
 * process death. Three profiles across two protocol families and two
 * categories are persisted; A→B→A must return distinct family, thresholds and
 * calibration, and deleting A must leave B and C intact.
 */
class DataStoreVehicleProfileStoreTest {

    private val a = "AA:BB:CC:DD:EE:FF"
    private val b = "11:22:33:44:55:66"
    private val c = "02:00:00:00:00:03"

    private fun profiles() = listOf(
        vehicleProfile(
            address = a,
            category = PlevCategory.WHEEL,
            family = WheelFamily.G,
            displayName = "Begode commuter",
            alertThresholds = AlertThresholds(speedLimitKmh = 35f, temperatureLimitC = 70f),
            calibration = VehicleCalibration(pitchOffsetDegrees = 1.5f),
        ),
        vehicleProfile(
            address = b,
            category = PlevCategory.WHEEL,
            family = WheelFamily.I2,
            displayName = "Inmotion tourer",
            alertThresholds = AlertThresholds(speedLimitKmh = 50f, lowBatteryPercent = 15f),
            calibration = VehicleCalibration(pitchOffsetDegrees = -2f, rollOffsetDegrees = 0.5f),
        ),
        vehicleProfile(
            address = c,
            category = PlevCategory.SCOOTER,
            family = null,
            displayName = "Ninebot scooter",
        ),
    )

    @Test
    fun threeProfilesAcrossTwoFamiliesSurviveARecreatedStoreAndKeepTheirScope() = runBlocking {
        val file = File.createTempFile("vehicle-profiles", ".preferences_pb")
        file.delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // One persisted DataStore, as the singleton delegate provides in
            // production. The *repository* is recreated below; the file is the
            // process-death boundary and must already contain the records.
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
            val store1 = DataStoreVehicleProfileStore(dataStore, scope)
            profiles().forEach { store1.upsert(it) }
            awaitProfiles(store1, 3)
            assertTrue("the profile file must be written before a restart", file.length() > 0L)

            val restarted = DataStoreVehicleProfileStore(dataStore, scope)
            val restored = awaitProfiles(restarted, 3)
            assertEquals(setOf(WheelFamily.G, WheelFamily.I2), restored.mapNotNull { it.family }.toSet())
            assertEquals(2, restored.count { it.category == PlevCategory.WHEEL })
            assertEquals(1, restored.count { it.category == PlevCategory.SCOOTER })
            assertEquals(mapOf(a to WheelFamily.G, b to WheelFamily.I2), restarted.familyHints.value)

            // A → B → A: switching must not leak thresholds, calibration or
            // family from the previously selected vehicle.
            val firstA = restarted.profile(a)!!
            val firstB = restarted.profile(b)!!
            val secondA = restarted.profile(a)!!
            assertEquals(WheelFamily.G, firstA.family)
            assertEquals(35f, firstA.alertThresholds!!.speedLimitKmh)
            assertEquals(1.5f, firstA.calibration.pitchOffsetDegrees)
            assertEquals(WheelFamily.I2, firstB.family)
            assertEquals(50f, firstB.alertThresholds!!.speedLimitKmh)
            assertEquals(-2f, firstB.calibration.pitchOffsetDegrees)
            assertEquals(firstA, secondA)

            // Deleting A leaves B and C untouched.
            restarted.remove(a)
            awaitProfiles(restarted, 2)
            assertNull(restarted.profile(a))
            assertEquals(WheelFamily.I2, restarted.profile(b)!!.family)
            assertEquals(PlevCategory.SCOOTER, restarted.profile(c)!!.category)
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun concurrentAtomicUpdatesMergeAgainstTheLatestStoredRecord() = runBlocking {
        val file = File.createTempFile("vehicle-atomic", ".preferences_pb")
        file.delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
            val store = DataStoreVehicleProfileStore(dataStore, scope)
            store.upsert(vehicleProfile(
                address = a,
                category = PlevCategory.WHEEL,
                family = WheelFamily.G,
                displayName = "Original",
            ))
            awaitProfiles(store, 1)

            kotlinx.coroutines.coroutineScope {
                launch { store.update(a) { it.copy(displayName = "New name") } }
                launch { store.update(a) { it.copy(family = WheelFamily.V) } }
            }
            val merged = store.profile(a)!!
            assertEquals("New name", merged.displayName)
            assertEquals(WheelFamily.V, merged.family)
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun malformedOrCategoryUnknownRecordsAreDroppedInsteadOfGuessed() = runBlocking {
        val file = File.createTempFile("vehicle-profiles-bad", ".preferences_pb")
        file.delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("vehicle_AA:BB:CC:DD:EE:FF")] = "v1|not-an-address|bad"
                preferences[stringPreferencesKey("vehicle_11:22:33:44:55:66")] =
                    listOf("v1", b, b, "NOT_A_CATEGORY", "", "name", "", "", "", "", "", "", "", "0", "0", "")
                        .joinToString("|")
            }
            val store = DataStoreVehicleProfileStore(dataStore, scope)
            // The StateFlow is eager; reading the cold store must also drop both.
            assertEquals(emptyList<Any>(), store.current())
            assertTrue(store.profiles.first().isEmpty())
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun legacyV1RecordMigratesToAnUnprovenancedProfileAndWritesV2() = runBlocking {
        val file = File.createTempFile("vehicle-v1-migration", ".preferences_pb")
        file.delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
            val legacy = listOf(
                "v1", a, a, "WHEEL", "G", "Legacy alias", "V11Y", "1.3.0",
                "35", "70", "10", "80", "true", "1.5", "-0.5", "legacy note",
            ).joinToString("|")
            dataStore.edit { it[stringPreferencesKey("vehicle_$a")] = legacy }
            val store = DataStoreVehicleProfileStore(dataStore, scope)
            val before = awaitProfiles(store, 1).single()
            assertEquals("V11Y", before.modelName)
            assertEquals("1.3.0", before.firmwareVersion)
            assertNull("v1 data has no evidence class", before.identityEvidence)
            assertNull(before.transportHint)
            assertEquals("Legacy alias", before.displayName)
            assertEquals(1.5f, before.calibration.pitchOffsetDegrees)

            val updated = store.updateIdentifiedIdentity(
                address = a,
                modelName = "V12",
                firmwareVersion = "1.4.0",
                family = WheelFamily.I2,
                evidence = WheelIdentityEvidence.MODEL_AND_FIRMWARE,
            )
            assertNotNull(updated)
            assertEquals(WheelIdentityEvidence.MODEL_AND_FIRMWARE, updated!!.identityEvidence)
            val raw = dataStore.data.first()[stringPreferencesKey("vehicle_$a")]!!
            assertTrue("new records must be written as v2", raw.startsWith("v2|"))
            // A recreated store reads the migrated evidence and preserves the alias.
            val restarted = DataStoreVehicleProfileStore(dataStore, scope)
            val after = awaitProfiles(restarted, 1).single()
            assertEquals("V12", after.modelName)
            assertEquals(WheelIdentityEvidence.MODEL_AND_FIRMWARE, after.identityEvidence)
            assertEquals("Legacy alias", after.displayName)
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    private suspend fun awaitProfiles(
        store: DataStoreVehicleProfileStore,
        count: Int,
    ) = withTimeout(5_000) { store.profiles.first { it.size == count } }
}
