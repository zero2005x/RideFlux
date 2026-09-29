/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.backup

import com.rideflux.app.backup.TripBackupManager.JsonParser
import com.rideflux.app.backup.TripBackupManager.JsonValue
import com.rideflux.domain.ride.ImportResult
import com.rideflux.domain.ride.Trip
import com.rideflux.domain.ride.TripRepository
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The backup file is parsed by a hand-written JSON reader, so its edges are worth pinning. */
class TripBackupJsonTest {

    private fun parse(src: String): JsonValue = JsonParser(src).parse()

    private fun failure(src: String): String =
        assertThrows(IllegalArgumentException::class.java) { parse(src) }.message.orEmpty()

    @Test
    fun parsesEveryValueKindAndEscape() {
        val root = parse(
            """
            { "s": "q\"\\\/\b\f\n\r\tA\x",
              "n": -1.5e2, "t": true, "f": false, "z": null,
              "a": [1, {}, []], "o": {} }
            """.trimIndent(),
        ) as JsonValue.JsonObject

        assertEquals("q\"\\/\b\u000c\n\r\tAx", root.getString("s"))
        assertEquals(-150.0, root.getDouble("n")!!, 0.0)
        assertEquals(-150L, root.getLong("n"))
        assertEquals(-150, root.getInt("n"))
        assertEquals(-150f, root.getFloat("n")!!, 0f)
        assertEquals(true, root.getBoolean("t"))
        assertEquals(false, root.getBoolean("f"))
        assertEquals(JsonValue.JsonNull, root.map["z"])
        assertEquals(3, root.getArray("a")!!.list.size)
        assertTrue(root.getObject("o")!!.map.isEmpty())
        assertNull(root.getString("missing"))
        assertNull(root.getObject("s"))
    }

    @Test
    fun rejectsMalformedDocumentsWithAPositionedMessage() {
        assertTrue(failure("").contains("Unexpected end of JSON"))
        assertTrue(failure("?").contains("Unexpected char '?'"))
        assertTrue(failure("{1:2}").contains("Expected string key"))
        assertTrue(failure("{\"a\" 1}").contains("Expected ':'"))
        assertTrue(failure("{\"a\":1 \"b\":2}").contains("Expected ',' or '}'"))
        assertTrue(failure("[1 2]").contains("Expected ',' or ']'"))
        assertTrue(failure("\"abc").contains("Unterminated string"))
        assertTrue(failure("\"ab\\").contains("Unterminated escape"))
        assertTrue(failure("tru").contains("Expected boolean"))
        assertTrue(failure("nul").contains("Expected null"))
    }

    @Test
    fun exportEscapesControlCharactersSoTheyRoundTrip() = runBlocking {
        val nasty = "a\"b\\c\b\u000c\n\r\td"
        val trips = mockk<TripRepository>()
        val settings = mockk<SettingsRepository>()
        coEvery { trips.getAllTrips() } returns listOf(
            Trip(id = 7L, wheelAddress = nasty, wheelModel = "m\"x", startedAtMillis = 1L),
        )
        coEvery { trips.getSamples(7L) } returns emptyList()
        coEvery { settings.current() } returns AppSettings(hudPeerMac = nasty, preferredGlassesMac = nasty)

        val out = ByteArrayOutputStream()
        assertEquals(1, TripBackupManager(trips, settings).exportData(out))

        val json = ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            assertEquals(TripBackupManager.BACKUP_FILE_NAME, zip.nextEntry.name)
            zip.readBytes().toString(Charsets.UTF_8)
        }
        val root = parse(json) as JsonValue.JsonObject
        val trip = root.getArray("trips")!!.list.single() as JsonValue.JsonObject
        assertEquals(nasty, trip.getString("wheelAddress"))
        assertEquals("m\"x", trip.getString("wheelModel"))
        assertEquals(nasty, root.getObject("settings")!!.getString("hudPeerMac"))
        assertEquals(nasty, root.getObject("settings")!!.getString("preferredGlassesMac"))
    }

    @Test
    fun importRejectsARootThatIsNotAnObject() = runBlocking {
        val manager = TripBackupManager(mockk(), mockk())
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { manager.importData(ByteArrayInputStream(zipOf("[]")), replaceAll = false) }
        }
        assertTrue(error.message.orEmpty().contains("root must be a JSON object"))
    }

    @Test
    fun importKeepsCurrentSettingsForKeysTheBackupOmits() = runBlocking {
        val current = AppSettings(useMetric = false, hudMirrorHorizontally = true)
        val settings = mockk<SettingsRepository>()
        val trips = mockk<TripRepository>()
        val saved = slot<AppSettings>()
        coEvery { settings.current() } returns current
        coEvery { settings.updateSettings(capture(saved)) } returns Unit
        coEvery { trips.importTrips(any(), any()) } returns ImportResult(0, 0, 0)

        val json = """{"version":1,"settings":{"bridgeStandbyAdvertiseLowLatency":true,"alertsEnabled":false}}"""
        val result = TripBackupManager(trips, settings)
            .importData(ByteArrayInputStream(zipOf(json)), replaceAll = true)

        assertEquals(ImportResult(0, 0, 0), result)
        assertTrue(saved.captured.bridgeStandbyAdvertiseLowLatency)
        assertEquals(false, saved.captured.alertThresholds.enabled)
        // Untouched keys fall back to what is already stored.
        assertEquals(false, saved.captured.useMetric)
        assertEquals(true, saved.captured.hudMirrorHorizontally)
        coVerify(exactly = 1) { trips.importTrips(emptyList(), true) }
    }

    private fun zipOf(json: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(TripBackupManager.BACKUP_FILE_NAME))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }
}
