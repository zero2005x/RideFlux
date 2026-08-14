/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.trips

import android.content.ContentResolver
import android.net.Uri
import com.rideflux.domain.ride.Trip
import com.rideflux.domain.ride.TripSample
import java.time.Instant
import java.util.Locale

object TripExporter {
    private const val CSV_HEADER =
        "tripId,timestampMillis,speedKmh,voltageV,currentA,batteryPercent,pwmPercent," +
            "mosTemperatureC,latitudeDeg,longitudeDeg,altitudeM"

    fun writeCsv(resolver: ContentResolver, uri: Uri, trip: Trip, samples: List<TripSample>) {
        resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
            writer.appendLine(CSV_HEADER)
            samples.forEach { s ->
                writer.appendLine(
                    listOf(
                        trip.id, s.timestampMillis, s.speedKmh, s.voltageV, s.currentA,
                        s.batteryPercent, s.pwmPercent, s.mosTemperatureC,
                        s.latitudeDeg, s.longitudeDeg, s.altitudeM,
                    ).joinToString(",") { value -> value?.toString() ?: "" }
                )
            }
        } ?: error("Unable to open export destination")
    }

    fun writeGpx(resolver: ContentResolver, uri: Uri, trip: Trip, samples: List<TripSample>) {
        resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
            writer.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            writer.appendLine("<gpx version=\"1.1\" creator=\"RideFlux\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:rideflux=\"https://rideflux.app/gpx/1\">")
            writer.appendLine("  <trk><name>${xmlEscape(trip.wheelModel ?: trip.wheelAddress)}</name><trkseg>")
            samples.forEach { s ->
                val lat = s.latitudeDeg ?: return@forEach
                val lon = s.longitudeDeg ?: return@forEach
                writer.appendLine("    <trkpt lat=\"${decimal(lat)}\" lon=\"${decimal(lon)}\">")
                s.altitudeM?.let { writer.appendLine("      <ele>${decimal(it)}</ele>") }
                writer.appendLine("      <time>${Instant.ofEpochMilli(s.timestampMillis)}</time>")
                writer.appendLine("      <extensions>")
                s.speedKmh?.let { writer.appendLine("        <rideflux:speedKmh>${decimal(it.toDouble())}</rideflux:speedKmh>") }
                s.voltageV?.let { writer.appendLine("        <rideflux:voltageV>${decimal(it.toDouble())}</rideflux:voltageV>") }
                writer.appendLine("      </extensions>")
                writer.appendLine("    </trkpt>")
            }
            writer.appendLine("  </trkseg></trk>")
            writer.appendLine("</gpx>")
        } ?: error("Unable to open export destination")
    }

    private fun decimal(value: Double): String = String.format(Locale.US, "%.7f", value)
    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
