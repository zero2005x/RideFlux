/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.backup

import com.rideflux.app.BuildConfig
import com.rideflux.domain.ride.ImportResult
import com.rideflux.domain.ride.Trip
import com.rideflux.domain.ride.TripRepository
import com.rideflux.domain.ride.TripSample
import com.rideflux.domain.settings.AlertThresholds
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripBackupManager @Inject constructor(
    private val tripRepository: TripRepository,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val BACKUP_FILE_NAME = "rideflux_backup.json"
        const val CURRENT_SCHEMA_VERSION = 1

        private fun escapeJson(s: String): String = buildString {
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }
    }

    suspend fun exportData(outputStream: OutputStream): Int {
        val trips = tripRepository.getAllTrips()
        val settings = settingsRepository.current()

        val jsonString = buildString {
            append("{\n")
            append("  \"version\": $CURRENT_SCHEMA_VERSION,\n")
            append("  \"exportedAtMillis\": ${System.currentTimeMillis()},\n")
            append("  \"appVersionCode\": ${BuildConfig.VERSION_CODE},\n")
            append("  \"appVersionName\": \"${escapeJson(BuildConfig.VERSION_NAME)}\",\n")
            // Settings: strictly excludes pairing tokens or private keys
            append("  \"settings\": {\n")
            append("    \"speedLimitKmh\": ${settings.alertThresholds.speedLimitKmh},\n")
            append("    \"temperatureLimitC\": ${settings.alertThresholds.temperatureLimitC},\n")
            append("    \"lowBatteryPercent\": ${settings.alertThresholds.lowBatteryPercent},\n")
            append("    \"pwmAlertPercent\": ${settings.alertThresholds.pwmAlertPercent},\n")
            append("    \"alertsEnabled\": ${settings.alertThresholds.enabled},\n")
            append("    \"useMetric\": ${settings.useMetric},\n")
            append("    \"keepScreenOnDashboard\": ${settings.keepScreenOnDashboard},\n")
            append("    \"bridgeAutostart\": ${settings.bridgeAutostart},\n")
            append("    \"bridgeStandbyAdvertiseLowLatency\": ${settings.bridgeStandbyAdvertiseLowLatency},\n")
            append("    \"hudMirrorHorizontally\": ${settings.hudMirrorHorizontally}")
            val ringKey = settings.ringKeyCode
            if (ringKey != null) {
                append(",\n    \"ringKeyCode\": $ringKey")
            }
            val peerMac = settings.hudPeerMac
            if (peerMac != null) {
                append(",\n    \"hudPeerMac\": \"${escapeJson(peerMac)}\"")
            }
            val preferredMac = settings.preferredGlassesMac
            if (preferredMac != null) {
                append(",\n    \"preferredGlassesMac\": \"${escapeJson(preferredMac)}\"")
            }
            append("\n  },\n")
            append("  \"trips\": [\n")
            for ((tripIndex, trip) in trips.withIndex()) {
                append("    {\n")
                append("      \"wheelAddress\": \"${escapeJson(trip.wheelAddress)}\",\n")
                val model = trip.wheelModel
                if (model != null) {
                    append("      \"wheelModel\": \"${escapeJson(model)}\",\n")
                }
                append("      \"startedAtMillis\": ${trip.startedAtMillis},\n")
                val ended = trip.endedAtMillis
                if (ended != null) {
                    append("      \"endedAtMillis\": $ended,\n")
                }
                append("      \"distanceMetres\": ${trip.distanceMetres},\n")
                append("      \"durationSeconds\": ${trip.durationSeconds}")
                trip.maxSpeedKmh?.let { append(",\n      \"maxSpeedKmh\": $it") }
                trip.avgSpeedKmh?.let { append(",\n      \"avgSpeedKmh\": $it") }
                trip.startBatteryPercent?.let { append(",\n      \"startBatteryPercent\": $it") }
                trip.endBatteryPercent?.let { append(",\n      \"endBatteryPercent\": $it") }
                trip.maxPwmPercent?.let { append(",\n      \"maxPwmPercent\": $it") }
                trip.maxMosTemperatureC?.let { append(",\n      \"maxMosTemperatureC\": $it") }
                append(",\n")

                val samples = tripRepository.getSamples(trip.id)
                append("      \"samples\": [\n")
                for ((sampleIndex, s) in samples.withIndex()) {
                    append("        {")
                    append("\"timestampMillis\":${s.timestampMillis}")
                    if (s.speedKmh != null) append(",\"speedKmh\":${s.speedKmh}")
                    if (s.voltageV != null) append(",\"voltageV\":${s.voltageV}")
                    if (s.currentA != null) append(",\"currentA\":${s.currentA}")
                    if (s.batteryPercent != null) append(",\"batteryPercent\":${s.batteryPercent}")
                    if (s.pwmPercent != null) append(",\"pwmPercent\":${s.pwmPercent}")
                    if (s.mosTemperatureC != null) append(",\"mosTemperatureC\":${s.mosTemperatureC}")
                    if (s.latitudeDeg != null) append(",\"latitudeDeg\":${s.latitudeDeg}")
                    if (s.longitudeDeg != null) append(",\"longitudeDeg\":${s.longitudeDeg}")
                    if (s.altitudeM != null) append(",\"altitudeM\":${s.altitudeM}")
                    append("}")
                    if (sampleIndex < samples.size - 1) append(",")
                    append("\n")
                }
                append("      ]\n")
                append("    }")
                if (tripIndex < trips.size - 1) append(",")
                append("\n")
            }
            append("  ]\n")
            append("}\n")
        }

        val jsonBytes = jsonString.toByteArray(StandardCharsets.UTF_8)

        ZipOutputStream(outputStream).use { zipOut ->
            val entry = ZipEntry(BACKUP_FILE_NAME)
            zipOut.putNextEntry(entry)
            zipOut.write(jsonBytes)
            zipOut.closeEntry()
            zipOut.finish()
        }
        return trips.size
    }

    suspend fun importData(inputStream: InputStream, replaceAll: Boolean): ImportResult {
        val jsonString = readBackupJsonFromZip(inputStream)
            ?: throw IllegalArgumentException("No backup JSON found in archive")

        val root = JsonParser(jsonString).parse() as? JsonValue.JsonObject
            ?: throw IllegalArgumentException("Invalid backup format: root must be a JSON object")

        val version = root.getLong("version")?.toInt() ?: -1
        if (version < 1 || version > CURRENT_SCHEMA_VERSION) {
            throw IllegalArgumentException("Unsupported backup version: $version")
        }

        // Restore settings if present
        root.getObject("settings")?.let { s ->
            val current = settingsRepository.current()
            val restored = AppSettings(
                alertThresholds = AlertThresholds(
                    speedLimitKmh = s.getFloat("speedLimitKmh") ?: current.alertThresholds.speedLimitKmh,
                    temperatureLimitC = s.getFloat("temperatureLimitC") ?: current.alertThresholds.temperatureLimitC,
                    lowBatteryPercent = s.getFloat("lowBatteryPercent") ?: current.alertThresholds.lowBatteryPercent,
                    pwmAlertPercent = s.getFloat("pwmAlertPercent") ?: current.alertThresholds.pwmAlertPercent,
                    enabled = s.getBoolean("alertsEnabled") ?: current.alertThresholds.enabled,
                ),
                useMetric = s.getBoolean("useMetric") ?: current.useMetric,
                keepScreenOnDashboard = s.getBoolean("keepScreenOnDashboard") ?: current.keepScreenOnDashboard,
                bridgeAutostart = s.getBoolean("bridgeAutostart") ?: current.bridgeAutostart,
                bridgeStandbyAdvertiseLowLatency = s.getBoolean("bridgeStandbyAdvertiseLowLatency")
                    ?: current.bridgeStandbyAdvertiseLowLatency,
                hudPeerMac = if (s.map.containsKey("hudPeerMac")) s.getString("hudPeerMac") else current.hudPeerMac,
                ringKeyCode = if (s.map.containsKey("ringKeyCode")) s.getInt("ringKeyCode") else current.ringKeyCode,
                hudMirrorHorizontally = s.getBoolean("hudMirrorHorizontally") ?: current.hudMirrorHorizontally,
                preferredGlassesMac = if (s.map.containsKey("preferredGlassesMac")) s.getString("preferredGlassesMac") else current.preferredGlassesMac,
            )
            settingsRepository.updateSettings(restored)
        }

        // Restore trips
        val tripsWithSamples = mutableListOf<Pair<Trip, List<TripSample>>>()
        val tripsArr = root.getArray("trips")?.list ?: emptyList()
        for (item in tripsArr) {
            val tripObj = item as? JsonValue.JsonObject ?: continue
            val wheelAddress = tripObj.getString("wheelAddress") ?: continue
            val startedAtMillis = tripObj.getLong("startedAtMillis") ?: continue

            val trip = Trip(
                wheelAddress = wheelAddress,
                wheelModel = tripObj.getString("wheelModel")?.takeIf(String::isNotBlank),
                startedAtMillis = startedAtMillis,
                endedAtMillis = tripObj.getLong("endedAtMillis"),
                distanceMetres = tripObj.getDouble("distanceMetres") ?: 0.0,
                durationSeconds = tripObj.getLong("durationSeconds") ?: 0L,
                maxSpeedKmh = tripObj.getFloat("maxSpeedKmh"),
                avgSpeedKmh = tripObj.getFloat("avgSpeedKmh"),
                startBatteryPercent = tripObj.getFloat("startBatteryPercent"),
                endBatteryPercent = tripObj.getFloat("endBatteryPercent"),
                maxPwmPercent = tripObj.getFloat("maxPwmPercent"),
                maxMosTemperatureC = tripObj.getFloat("maxMosTemperatureC"),
            )

            val samplesList = mutableListOf<TripSample>()
            val samplesArr = tripObj.getArray("samples")?.list ?: emptyList()
            for (sampleItem in samplesArr) {
                val sObj = sampleItem as? JsonValue.JsonObject ?: continue
                val timestampMillis = sObj.getLong("timestampMillis") ?: continue
                val sample = TripSample(
                    tripId = 0L,
                    timestampMillis = timestampMillis,
                    speedKmh = sObj.getFloat("speedKmh"),
                    voltageV = sObj.getFloat("voltageV"),
                    currentA = sObj.getFloat("currentA"),
                    batteryPercent = sObj.getFloat("batteryPercent"),
                    pwmPercent = sObj.getFloat("pwmPercent"),
                    mosTemperatureC = sObj.getFloat("mosTemperatureC"),
                    latitudeDeg = sObj.getDouble("latitudeDeg"),
                    longitudeDeg = sObj.getDouble("longitudeDeg"),
                    altitudeM = sObj.getDouble("altitudeM"),
                )
                samplesList.add(sample)
            }
            tripsWithSamples.add(trip to samplesList)
        }

        return tripRepository.importTrips(tripsWithSamples, replaceAll)
    }

    private fun readBackupJsonFromZip(inputStream: InputStream): String? {
        val zipIn = ZipInputStream(inputStream)
        var entry = zipIn.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true)) {
                return zipIn.bufferedReader(StandardCharsets.UTF_8).readText()
            }
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }
        return null
    }

    /**
     * Minimal, zero-dependency pure Kotlin JSON AST and recursive descent parser.
     * Guarantees identical execution on JVM unit tests and Android runtime without
     * needing Android SDK jar stubs or external libraries.
     */
    sealed interface JsonValue {
        data class JsonObject(val map: Map<String, JsonValue>) : JsonValue {
            fun getObject(key: String): JsonObject? = map[key] as? JsonObject
            fun getArray(key: String): JsonArray? = map[key] as? JsonArray
            fun getString(key: String): String? = (map[key] as? JsonString)?.value
            fun getLong(key: String): Long? = (map[key] as? JsonNumber)?.toLong()
            fun getInt(key: String): Int? = (map[key] as? JsonNumber)?.toInt()
            fun getDouble(key: String): Double? = (map[key] as? JsonNumber)?.toDouble()
            fun getFloat(key: String): Float? = (map[key] as? JsonNumber)?.toFloat()
            fun getBoolean(key: String): Boolean? = (map[key] as? JsonBoolean)?.value
        }
        data class JsonArray(val list: List<JsonValue>) : JsonValue
        data class JsonString(val value: String) : JsonValue
        data class JsonNumber(val raw: String) : JsonValue {
            fun toLong(): Long = raw.toDouble().toLong()
            fun toInt(): Int = raw.toDouble().toInt()
            fun toDouble(): Double = raw.toDouble()
            fun toFloat(): Float = raw.toFloat()
        }
        data class JsonBoolean(val value: Boolean) : JsonValue
        data object JsonNull : JsonValue
    }

    class JsonParser(private val src: String) {
        private var idx = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val res = parseValue()
            skipWhitespace()
            return res
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            if (idx >= src.length) throw IllegalArgumentException("Unexpected end of JSON")
            return when (val c = src[idx]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected char '$c' at position $idx")
            }
        }

        private fun parseObject(): JsonValue.JsonObject {
            expect('{')
            val map = mutableMapOf<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                idx++
                return JsonValue.JsonObject(map)
            }
            while (true) {
                skipWhitespace()
                val key = (parseValue() as? JsonValue.JsonString)?.value
                    ?: throw IllegalArgumentException("Expected string key in object at $idx")
                skipWhitespace()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                val next = peek()
                if (next == ',') {
                    idx++
                } else if (next == '}') {
                    idx++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or '}' at $idx, got '$next'")
                }
            }
            return JsonValue.JsonObject(map)
        }

        private fun parseArray(): JsonValue.JsonArray {
            expect('[')
            val list = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                idx++
                return JsonValue.JsonArray(list)
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                val next = peek()
                if (next == ',') {
                    idx++
                } else if (next == ']') {
                    idx++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or ']' at $idx, got '$next'")
                }
            }
            return JsonValue.JsonArray(list)
        }

        private fun parseString(): JsonValue.JsonString {
            expect('"')
            val sb = StringBuilder()
            while (idx < src.length) {
                val c = src[idx++]
                if (c == '"') {
                    return JsonValue.JsonString(sb.toString())
                } else if (c == '\\') {
                    if (idx >= src.length) throw IllegalArgumentException("Unterminated escape")
                    when (val esc = src[idx++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = src.substring(idx, idx + 4)
                            idx += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(): JsonValue.JsonNumber {
            val start = idx
            if (src[idx] == '-') idx++
            while (idx < src.length && (src[idx].isDigit() || src[idx] == '.' || src[idx] == 'e' || src[idx] == 'E' || src[idx] == '+' || src[idx] == '-')) {
                idx++
            }
            return JsonValue.JsonNumber(src.substring(start, idx))
        }

        private fun parseBoolean(): JsonValue.JsonBoolean {
            if (src.startsWith("true", idx)) {
                idx += 4
                return JsonValue.JsonBoolean(true)
            }
            if (src.startsWith("false", idx)) {
                idx += 5
                return JsonValue.JsonBoolean(false)
            }
            throw IllegalArgumentException("Expected boolean at $idx")
        }

        private fun parseNull(): JsonValue.JsonNull {
            if (src.startsWith("null", idx)) {
                idx += 4
                return JsonValue.JsonNull
            }
            throw IllegalArgumentException("Expected null at $idx")
        }

        private fun skipWhitespace() {
            while (idx < src.length && src[idx].isWhitespace()) {
                idx++
            }
        }

        private fun peek(): Char = if (idx < src.length) src[idx] else '\u0000'

        private fun expect(expected: Char) {
            if (idx >= src.length || src[idx] != expected) {
                throw IllegalArgumentException("Expected '$expected' at $idx, got '${peek()}'")
            }
            idx++
        }
    }
}
