/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.dashboard.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rideflux.app.ui.dashboard.DashboardUiState
import com.rideflux.app.ui.dashboard.displayDistance
import com.rideflux.app.ui.dashboard.displaySpeed
import com.rideflux.app.ui.dashboard.distanceUnit
import com.rideflux.app.ui.dashboard.speedUnit
import com.rideflux.app.ui.dashboard.components.MetricCard
import com.rideflux.app.ui.dashboard.components.MetricRow
import com.rideflux.app.ui.dashboard.components.RideFluxColors
import com.rideflux.app.ui.dashboard.components.SectionHeader
import com.rideflux.app.recording.RecordingUiState
import com.rideflux.app.ui.trips.TripCard
import com.rideflux.app.ui.trips.TripHistoryViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/**
 * Page 5: Trips overview.
 *
 * RideFlux does not yet persist completed rides on disk, so this
 * page surfaces the in-progress ride only — odometer, trip
 * distance, max/avg speed, ride time. The card at the bottom
 * documents the planned persistence behaviour so the layout stays
 * representative of where the feature is heading.
 */
@Composable
fun TripsPage(
    state: DashboardUiState,
    recordingState: RecordingUiState,
    onStopRecording: () -> Unit,
    onOpenTrip: (Long) -> Unit,
    modifier: Modifier = Modifier,
    historyViewModel: TripHistoryViewModel = hiltViewModel(),
) {
    val allTrips by historyViewModel.trips.collectAsStateWithLifecycle()
    val address = state.identity?.address
    val recentTrips = allTrips.filter { address == null || it.wheelAddress == address }.take(5)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader("Current Ride", accent = RideFluxColors.Neon)
        if (recordingState.isRecording) {
            Button(onClick = onStopRecording) { Text("Stop recording") }
        }
        MetricRow(
            left = {
                MetricCard(
                    label = "Trip Distance",
                    value = state.displayDistance(state.tripDistanceMetres)
                        ?.let { "%.2f".format(Locale.US, it) } ?: "--",
                    unit = state.distanceUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Ride Time",
                    value = formatDuration(state.rideTimeSeconds),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        MetricRow(
            left = {
                MetricCard(
                    label = "Max Speed",
                    value = state.displaySpeed(state.maxSpeedKmh)?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = state.speedUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Avg Speed",
                    value = state.displaySpeed(state.avgSpeedKmh)?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = state.speedUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        SectionHeader("Lifetime", accent = RideFluxColors.Cyan)
        MetricRow(
            left = {
                MetricCard(
                    label = "Total Distance",
                    value = state.displayDistance(state.totalDistanceMetres)
                        ?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    unit = state.distanceUnit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                MetricCard(
                    label = "Vehicle",
                    value = state.identity?.modelName ?: "--",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        SectionHeader("Saved rides", accent = RideFluxColors.Warning)
        if (recentTrips.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Completed rides will appear here and remain available after restart.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            recentTrips.forEach { trip -> TripCard(trip, { onOpenTrip(trip.id) }) }
        }
    }
}
