/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.backup

import com.rideflux.domain.ride.ImportResult
import com.rideflux.domain.ride.Trip
import com.rideflux.domain.ride.TripRepository
import com.rideflux.domain.ride.TripSample
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class TripBackupManagerTest {

    @Test
    fun exportAndImport_roundtripRestoresTripsSamplesAndSettings() = runBlocking {
        val tripRepo = InMemoryTripRepository()
        val settingsRepo = InMemorySettingsRepository()

        settingsRepo.updateSettings(
            AppSettings(
                alertThresholds = AlertThresholds(
                    speedLimitKmh = 52f,
                    temperatureLimitC = 85f,
                    lowBatteryPercent = 20f,
                    pwmAlertPercent = 88f,
                    enabled = true,
                ),
                useMetric = false,
                keepScreenOnDashboard = false,
                bridgeAutostart = false,
                bridgeStandbyAdvertiseLowLatency = true,
                hudPeerMac = "AA:BB:CC:11:22:33",
                ringKeyCode = 24,
                hudMirrorHorizontally = true,
                preferredGlassesMac = "FF:EE:DD:33:22:11",
            ),
        )

        val tripId1 = tripRepo.createTrip(
            Trip(
                wheelAddress = "AA:BB:CC:DD:EE:01",
                wheelModel = "Veteran Patton",
                startedAtMillis = 1000L,
                endedAtMillis = 2000L,
                distanceMetres = 500.0,
                durationSeconds = 60,
                maxSpeedKmh = 35f,
                avgSpeedKmh = 25f,
                startBatteryPercent = 90f,
                endBatteryPercent = 85f,
                maxPwmPercent = 60f,
                maxMosTemperatureC = 45f,
            ),
        )
        tripRepo.appendSample(
            TripSample(
                tripId = tripId1,
                timestampMillis = 1010L,
                speedKmh = 20f,
                voltageV = 100f,
                currentA = 5f,
                batteryPercent = 89f,
                pwmPercent = 30f,
                mosTemperatureC = 40f,
                latitudeDeg = 25.0,
                longitudeDeg = 121.5,
                altitudeM = 15.0,
            ),
        )
        tripRepo.appendSample(
            TripSample(
                tripId = tripId1,
                timestampMillis = 1020L,
                speedKmh = 25f,
                voltageV = 99f,
                currentA = 6f,
                batteryPercent = 88f,
                pwmPercent = 35f,
                mosTemperatureC = 42f,
                latitudeDeg = 25.01,
                longitudeDeg = 121.51,
                altitudeM = 16.0,
            ),
        )

        val tripId2 = tripRepo.createTrip(
            Trip(
                wheelAddress = "AA:BB:CC:DD:EE:02",
                wheelModel = "Leaperkim Lynx",
                startedAtMillis = 5000L,
                endedAtMillis = 6000L,
                distanceMetres = 1200.0,
                durationSeconds = 120,
                maxSpeedKmh = 45f,
                avgSpeedKmh = 30f,
                startBatteryPercent = 95f,
                endBatteryPercent = 90f,
                maxPwmPercent = 70f,
                maxMosTemperatureC = 50f,
            ),
        )
        tripRepo.appendSample(
            TripSample(
                tripId = tripId2,
                timestampMillis = 5050L,
                speedKmh = 30f,
                voltageV = 130f,
                currentA = 10f,
                batteryPercent = 94f,
                pwmPercent = 40f,
                mosTemperatureC = 46f,
                latitudeDeg = 25.05,
                longitudeDeg = 121.55,
                altitudeM = 20.0,
            ),
        )

        val backupManager = TripBackupManager(tripRepo, settingsRepo)

        // 1. Export to byte array
        val outBytes = ByteArrayOutputStream()
        val exportedCount = backupManager.exportData(outBytes)
        assertEquals(2, exportedCount)

        // 2. Validate ZIP content and token exclusion
        val zipIn = ZipInputStream(ByteArrayInputStream(outBytes.toByteArray()))
        var jsonContent: String? = null
        var entry = zipIn.nextEntry
        while (entry != null) {
            if (entry.name == TripBackupManager.BACKUP_FILE_NAME) {
                jsonContent = zipIn.bufferedReader(StandardCharsets.UTF_8).readText()
            }
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }
        assertNotNull("Backup ZIP must contain rideflux_backup.json", jsonContent)

        val rootJson = TripBackupManager.JsonParser(jsonContent!!).parse() as TripBackupManager.JsonValue.JsonObject
        assertEquals(1, rootJson.getLong("version")?.toInt())
        assertTrue(rootJson.getLong("exportedAtMillis") != null)
        assertTrue(rootJson.getLong("appVersionCode") != null)
        assertTrue(rootJson.getString("appVersionName") != null)

        // Token Exclusion Verification: ensure no tokens are in JSON
        assertFalse(jsonContent.contains("pairing_token"))
        assertFalse(jsonContent.contains("pairingToken"))
        assertFalse(jsonContent.contains("clientSecret"))
        assertFalse(jsonContent.contains("snAuth"))

        // 3. Import into clean repositories
        val freshTripRepo = InMemoryTripRepository()
        val freshSettingsRepo = InMemorySettingsRepository()
        val freshBackupManager = TripBackupManager(freshTripRepo, freshSettingsRepo)

        val importResult = freshBackupManager.importData(
            ByteArrayInputStream(outBytes.toByteArray()),
            replaceAll = false,
        )

        assertEquals(2, importResult.tripsImported)
        assertEquals(0, importResult.tripsSkipped)
        assertEquals(3, importResult.samplesImported)

        // Verify restored settings
        val restoredSettings = freshSettingsRepo.current()
        assertEquals(52f, restoredSettings.alertThresholds.speedLimitKmh)
        assertEquals(85f, restoredSettings.alertThresholds.temperatureLimitC)
        assertEquals(20f, restoredSettings.alertThresholds.lowBatteryPercent)
        assertEquals(88f, restoredSettings.alertThresholds.pwmAlertPercent)
        assertFalse(restoredSettings.useMetric)
        assertFalse(restoredSettings.keepScreenOnDashboard)
        assertFalse(restoredSettings.bridgeAutostart)
        assertTrue(restoredSettings.bridgeStandbyAdvertiseLowLatency)
        assertEquals("AA:BB:CC:11:22:33", restoredSettings.hudPeerMac)
        assertEquals(24, restoredSettings.ringKeyCode)
        assertTrue(restoredSettings.hudMirrorHorizontally)
        assertEquals("FF:EE:DD:33:22:11", restoredSettings.preferredGlassesMac)

        // Verify restored trips
        val restoredTrips = freshTripRepo.getAllTrips()
        assertEquals(2, restoredTrips.size)
        val t1 = restoredTrips.first { it.wheelAddress == "AA:BB:CC:DD:EE:01" }
        assertEquals("Veteran Patton", t1.wheelModel)
        assertEquals(1000L, t1.startedAtMillis)
        assertEquals(500.0, t1.distanceMetres, 0.001)

        val s1 = freshTripRepo.getSamples(t1.id)
        assertEquals(2, s1.size)
        assertEquals(1010L, s1[0].timestampMillis)
        assertEquals(20f, s1[0].speedKmh)
        assertEquals(1020L, s1[1].timestampMillis)
        assertEquals(25f, s1[1].speedKmh)

        val t2 = restoredTrips.first { it.wheelAddress == "AA:BB:CC:DD:EE:02" }
        assertEquals("Leaperkim Lynx", t2.wheelModel)
        assertEquals(5000L, t2.startedAtMillis)
        val s2 = freshTripRepo.getSamples(t2.id)
        assertEquals(1, s2.size)
        assertEquals(5050L, s2[0].timestampMillis)
    }

    @Test
    fun importData_mergeModeSkipsExistingDuplicates() = runBlocking {
        val tripRepo = InMemoryTripRepository()
        val settingsRepo = InMemorySettingsRepository()
        val backupManager = TripBackupManager(tripRepo, settingsRepo)

        // Seed 1 trip
        tripRepo.createTrip(
            Trip(
                wheelAddress = "AA:BB:CC:DD:EE:01",
                startedAtMillis = 1000L,
                distanceMetres = 300.0,
            ),
        )

        // Prepare backup with 2 trips (one duplicate, one new)
        val jsonString = """
        {
          "version": 1,
          "trips": [
            {
              "wheelAddress": "AA:BB:CC:DD:EE:01",
              "startedAtMillis": 1000,
              "distanceMetres": 300.0,
              "durationSeconds": 30
            },
            {
              "wheelAddress": "AA:BB:CC:DD:EE:02",
              "startedAtMillis": 2000,
              "distanceMetres": 800.0,
              "durationSeconds": 60
            }
          ]
        }
        """.trimIndent()

        val zipBytes = createZipWithContent(TripBackupManager.BACKUP_FILE_NAME, jsonString)

        val result = backupManager.importData(ByteArrayInputStream(zipBytes), replaceAll = false)
        assertEquals(1, result.tripsImported)
        assertEquals(1, result.tripsSkipped)
        assertEquals(2, tripRepo.getAllTrips().size)
    }

    @Test
    fun importData_replaceAllClearsExistingAndRestoresAll() = runBlocking {
        val tripRepo = InMemoryTripRepository()
        val settingsRepo = InMemorySettingsRepository()
        val backupManager = TripBackupManager(tripRepo, settingsRepo)

        // Seed 1 existing trip
        tripRepo.createTrip(
            Trip(
                wheelAddress = "OLD:OLD:OLD:OLD:OLD",
                startedAtMillis = 9999L,
                distanceMetres = 100.0,
            ),
        )

        // Prepare backup with 2 trips
        val jsonString = """
        {
          "version": 1,
          "trips": [
            {
              "wheelAddress": "NEW:01",
              "startedAtMillis": 1000,
              "distanceMetres": 300.0
            },
            {
              "wheelAddress": "NEW:02",
              "startedAtMillis": 2000,
              "distanceMetres": 800.0
            }
          ]
        }
        """.trimIndent()

        val zipBytes = createZipWithContent(TripBackupManager.BACKUP_FILE_NAME, jsonString)

        val result = backupManager.importData(ByteArrayInputStream(zipBytes), replaceAll = true)
        assertEquals(2, result.tripsImported)
        assertEquals(0, result.tripsSkipped)

        val currentTrips = tripRepo.getAllTrips()
        assertEquals(2, currentTrips.size)
        assertFalse(currentTrips.any { it.wheelAddress == "OLD:OLD:OLD:OLD:OLD" })
    }

    @Test
    fun importData_unsupportedVersionThrowsIllegalArgumentException() = runBlocking {
        val tripRepo = InMemoryTripRepository()
        val settingsRepo = InMemorySettingsRepository()
        val backupManager = TripBackupManager(tripRepo, settingsRepo)

        val jsonString = """{"version": 999}"""
        val zipBytes = createZipWithContent(TripBackupManager.BACKUP_FILE_NAME, jsonString)

        try {
            backupManager.importData(ByteArrayInputStream(zipBytes), replaceAll = false)
            fail("Should throw IllegalArgumentException on unsupported version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unsupported backup version") == true)
        }
    }

    @Test
    fun importData_noJsonInZipThrowsIllegalArgumentException() = runBlocking {
        val tripRepo = InMemoryTripRepository()
        val settingsRepo = InMemorySettingsRepository()
        val backupManager = TripBackupManager(tripRepo, settingsRepo)

        val zipBytes = createZipWithContent("some_other_file.txt", "hello")

        try {
            backupManager.importData(ByteArrayInputStream(zipBytes), replaceAll = false)
            fail("Should throw IllegalArgumentException when no json found in zip")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("No backup JSON found") == true)
        }
    }

    private fun createZipWithContent(fileName: String, content: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zipOut ->
            zipOut.putNextEntry(ZipEntry(fileName))
            zipOut.write(content.toByteArray(StandardCharsets.UTF_8))
            zipOut.closeEntry()
            zipOut.finish()
        }
        return out.toByteArray()
    }

    private class InMemoryTripRepository : TripRepository {
        private var nextId = 1L
        val trips = mutableListOf<Trip>()
        val samples = mutableMapOf<Long, MutableList<TripSample>>()

        override suspend fun getAllTrips(): List<Trip> = trips.toList()

        override suspend fun getAllSamples(): List<TripSample> = samples.values.flatten()

        override suspend fun getSamples(tripId: Long): List<TripSample> =
            samples[tripId]?.toList() ?: emptyList()

        override suspend fun createTrip(trip: Trip): Long {
            val id = nextId++
            val assigned = trip.copy(id = id)
            trips.add(assigned)
            samples[id] = mutableListOf()
            return id
        }

        override suspend fun appendSample(sample: TripSample) {
            samples.getOrPut(sample.tripId) { mutableListOf() }.add(sample)
        }

        override suspend fun finishTrip(trip: Trip) {
            val idx = trips.indexOfFirst { it.id == trip.id }
            if (idx >= 0) trips[idx] = trip
        }

        override suspend fun deleteTrip(tripId: Long) {
            trips.removeAll { it.id == tripId }
            samples.remove(tripId)
        }

        override suspend fun clearAll() {
            trips.clear()
            samples.clear()
        }

        override suspend fun recoverIncompleteTrips() = Unit

        override suspend fun importTrips(
            tripsWithSamples: List<Pair<Trip, List<TripSample>>>,
            replaceAll: Boolean,
        ): ImportResult {
            if (replaceAll) {
                clearAll()
            }
            val existingKeys = if (replaceAll) {
                mutableSetOf()
            } else {
                trips.map { "${it.wheelAddress}_${it.startedAtMillis}" }.toMutableSet()
            }

            var importedTrips = 0
            var skippedTrips = 0
            var importedSamples = 0

            for ((trip, sampleList) in tripsWithSamples) {
                val key = "${trip.wheelAddress}_${trip.startedAtMillis}"
                if (!replaceAll && existingKeys.contains(key)) {
                    skippedTrips++
                    continue
                }
                val id = nextId++
                trips.add(trip.copy(id = id))
                existingKeys.add(key)
                importedTrips++

                if (sampleList.isNotEmpty()) {
                    val remapped = sampleList.map { it.copy(tripId = id) }
                    samples[id] = remapped.toMutableList()
                    importedSamples += remapped.size
                }
            }

            return ImportResult(
                tripsImported = importedTrips,
                tripsSkipped = skippedTrips,
                samplesImported = importedSamples,
            )
        }

        override fun observeTrips(wheelAddress: String?): Flow<List<Trip>> =
            flowOf(trips.filter { wheelAddress == null || it.wheelAddress == wheelAddress })

        override fun observeTrip(tripId: Long): Flow<Trip?> =
            flowOf(trips.firstOrNull { it.id == tripId })

        override fun observeSamples(tripId: Long): Flow<List<TripSample>> =
            flowOf(samples[tripId] ?: emptyList())
    }

    private class InMemorySettingsRepository : SettingsRepository {
        private val _settings = MutableStateFlow(AppSettings())
        override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

        override suspend fun current(): AppSettings = _settings.value

        override suspend fun updateSettings(settings: AppSettings) {
            _settings.value = settings
        }

        override suspend fun setSpeedLimitKmh(value: Float) {
            _settings.value = _settings.value.copy(
                alertThresholds = _settings.value.alertThresholds.copy(speedLimitKmh = value),
            )
        }
        override suspend fun setTemperatureLimitC(value: Float) {
            _settings.value = _settings.value.copy(
                alertThresholds = _settings.value.alertThresholds.copy(temperatureLimitC = value),
            )
        }
        override suspend fun setLowBatteryPercent(value: Float) {
            _settings.value = _settings.value.copy(
                alertThresholds = _settings.value.alertThresholds.copy(lowBatteryPercent = value),
            )
        }
        override suspend fun setPwmAlertPercent(value: Float) {
            _settings.value = _settings.value.copy(
                alertThresholds = _settings.value.alertThresholds.copy(pwmAlertPercent = value),
            )
        }
        override suspend fun setAlertsEnabled(value: Boolean) {
            _settings.value = _settings.value.copy(
                alertThresholds = _settings.value.alertThresholds.copy(enabled = value),
            )
        }
        override suspend fun setUseMetric(value: Boolean) {
            _settings.value = _settings.value.copy(useMetric = value)
        }
        override suspend fun setKeepScreenOnDashboard(value: Boolean) {
            _settings.value = _settings.value.copy(keepScreenOnDashboard = value)
        }
        override suspend fun setBridgeAutostart(value: Boolean) {
            _settings.value = _settings.value.copy(bridgeAutostart = value)
        }
        override suspend fun setBridgeStandbyAdvertiseLowLatency(value: Boolean) {
            _settings.value = _settings.value.copy(bridgeStandbyAdvertiseLowLatency = value)
        }
        override suspend fun setHudPeerMac(value: String?) {
            _settings.value = _settings.value.copy(hudPeerMac = value)
        }
    }
}
