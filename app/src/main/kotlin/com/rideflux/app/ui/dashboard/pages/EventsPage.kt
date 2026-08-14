/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rideflux.app.ui.dashboard.TimedAlert
import com.rideflux.app.ui.dashboard.DashboardAlert
import com.rideflux.app.ui.dashboard.components.RideFluxColors
import com.rideflux.app.ui.dashboard.components.SectionHeader
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.alert.ThresholdAlert
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Page 6: Events / alerts log.
 *
 * Renders [TimedAlert]s newest first. Each row carries a coloured
 * leading dot signalling severity (cyan = informational, orange =
 * caution, red = severe), the wall-clock timestamp, the alert
 * title and a short detail line.
 */
@Composable
fun EventsPage(events: List<TimedAlert>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SectionHeader("Events", accent = RideFluxColors.Warning)
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No events yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Sort defensively so the documented newest-first ordering
            // holds regardless of what the caller supplies. Memoised so
            // the O(n log n) sort only runs when the list changes, not on
            // every recomposition.
            val sortedEvents = remember(events) {
                events.sortedByDescending { it.timestampMillis }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sortedEvents) { evt ->
                    EventRow(evt)
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: TimedAlert) {
    val (title, body, severity) = describe(event.alert)
    val tint = when (severity) {
        Severity.Severe -> RideFluxColors.Danger
        Severity.Warn -> RideFluxColors.Warning
        Severity.Info -> RideFluxColors.Cyan
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(tint),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = formatTime(event.timestampMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (body.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private enum class Severity { Severe, Warn, Info }

private data class EventDescription(val title: String, val body: String, val severity: Severity)

private fun describe(alert: DashboardAlert): EventDescription = when (alert) {
    is DashboardAlert.Wheel -> describeWheel(alert.value)
    is DashboardAlert.Threshold -> when (val threshold = alert.value) {
        is ThresholdAlert.Overspeed -> EventDescription(
            "Speed limit",
            "${threshold.speedKmh} km/h exceeds ${threshold.limitKmh} km/h",
            Severity.Severe,
        )
        is ThresholdAlert.OverTemperature -> EventDescription(
            "MOS temperature",
            "${threshold.temperatureC}°C exceeds ${threshold.limitC}°C",
            Severity.Severe,
        )
        is ThresholdAlert.LowBattery -> EventDescription(
            "Low battery threshold",
            "${threshold.percent}% is below ${threshold.limitPercent}%",
            Severity.Warn,
        )
        is ThresholdAlert.PwmLoad -> EventDescription(
            "PWM load",
            "${threshold.pwmPercent}% exceeds ${threshold.limitPercent}%",
            Severity.Severe,
        )
    }
}

private fun describeWheel(alert: WheelAlert): EventDescription = when (alert) {
    is WheelAlert.TiltBack -> EventDescription(
        "Tilt-back",
        "Speed ${"%.0f".format(Locale.US, alert.speedKmh)} km/h · limit ${"%.0f".format(Locale.US, alert.limit)} km/h",
        Severity.Severe,
    )
    is WheelAlert.SpeedCutoff -> EventDescription(
        "Speed cutoff",
        "Motor cut at ${"%.0f".format(Locale.US, alert.speedKmh)} km/h",
        Severity.Severe,
    )
    is WheelAlert.LowBattery -> EventDescription(
        "Low battery",
        "Voltage ${"%.1f".format(Locale.US, alert.voltageV)} V",
        Severity.Warn,
    )
    is WheelAlert.OverTemperature -> EventDescription(
        "Over temperature",
        "${alert.source.name}" + (alert.temperatureC?.let { " · ${"%.0f".format(Locale.US, it)}°C" } ?: ""),
        Severity.Severe,
    )
    is WheelAlert.FallDown -> EventDescription(
        "Fall detected",
        "Wheel reports a fall event",
        Severity.Severe,
    )
    is WheelAlert.FaultSetChanged -> EventDescription(
        "Fault set changed",
        buildString {
            if (alert.added.isNotEmpty()) append("+${alert.added.size} faults ")
            if (alert.removed.isNotEmpty()) append("-${alert.removed.size} cleared")
        }.trim(),
        if (alert.added.isNotEmpty()) Severity.Warn else Severity.Info,
    )
    is WheelAlert.Raw -> EventDescription(
        "${alert.domain} raw 0x${alert.code.toString(16).uppercase()}",
        "${alert.payload.size} bytes",
        Severity.Info,
    )
}

// DateTimeFormatter is immutable and thread-safe. Resolve the system zone for
// each call so a runtime timezone change is reflected without recreating this file.
private val TIME_PATTERN = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

private fun formatTime(millis: Long): String =
    TIME_PATTERN.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
