/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.trips

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rideflux.app.ui.dashboard.components.ChartLegend
import com.rideflux.app.ui.dashboard.components.ChartSeries
import com.rideflux.app.ui.dashboard.components.TelemetryChart
import com.rideflux.app.ui.dashboard.pages.TrackCanvas
import com.rideflux.domain.ride.Trip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripHistoryRoute(
    onNavigateUp: () -> Unit,
    onOpenTrip: (Long) -> Unit,
    viewModel: TripHistoryViewModel = hiltViewModel(),
) {
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    TripHistoryScreen(trips, onNavigateUp, onOpenTrip, viewModel::clearAll)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripHistoryScreen(
    trips: List<Trip>,
    onNavigateUp: () -> Unit,
    onOpenTrip: (Long) -> Unit,
    onClearAll: () -> Unit,
) {
    var filter by remember { mutableStateOf<String?>(null) }
    val addresses = remember(trips) { trips.map(Trip::wheelAddress).distinct() }
    val visible = remember(trips, filter) { filter?.let { selected -> trips.filter { it.wheelAddress == selected } } ?: trips }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip history") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { if (trips.isNotEmpty()) TextButton(onClick = onClearAll) { Text("Clear all") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (addresses.size > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                    addresses.take(3).forEach { address ->
                        FilterChip(
                            selected = filter == address,
                            onClick = { filter = address },
                            label = { Text(address.takeLast(5)) },
                        )
                    }
                }
            }
            if (visible.isEmpty()) {
                Text(
                    "No saved rides yet",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible, key = Trip::id) { trip ->
                        TripCard(trip, { onOpenTrip(trip.id) }, Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun TripCard(trip: Trip, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(trip.wheelModel ?: trip.wheelAddress, fontWeight = FontWeight.SemiBold)
            Text(DATE_FORMAT.format(Date(trip.startedAtMillis)), style = MaterialTheme.typography.bodySmall)
            Text(
                "%.2f km  •  %s  •  max %s km/h".format(
                    Locale.US,
                    trip.distanceMetres / 1_000.0,
                    formatDuration(trip.durationSeconds),
                    trip.maxSpeedKmh?.let { "%.1f".format(Locale.US, it) } ?: "--",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TripDetailRoute(
    onNavigateUp: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: TripDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val trip = state.trip
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { viewModel.exportCsv(context.contentResolver, it) }
    }
    val gpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        uri?.let { viewModel.exportGpx(context.contentResolver, it) }
    }
    TripDetailScreen(
        state = state,
        onNavigateUp = onNavigateUp,
        onCsv = { csvLauncher.launch("rideflux-trip-${trip?.id ?: 0}.csv") },
        onGpx = { gpxLauncher.launch("rideflux-trip-${trip?.id ?: 0}.gpx") },
        onDelete = { viewModel.delete(onDeleted) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    state: TripDetailUiState,
    onNavigateUp: () -> Unit,
    onCsv: () -> Unit,
    onGpx: () -> Unit,
    onDelete: () -> Unit,
) {
    val trip = state.trip
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (trip == null) {
            Text("Trip not found", Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(trip.wheelModel ?: trip.wheelAddress, style = MaterialTheme.typography.headlineSmall)
            Text(
                "%.2f km  •  %s  •  avg %s km/h  •  max %s km/h".format(
                    Locale.US,
                    trip.distanceMetres / 1_000.0,
                    formatDuration(trip.durationSeconds),
                    trip.avgSpeedKmh?.let { "%.1f".format(Locale.US, it) } ?: "--",
                    trip.maxSpeedKmh?.let { "%.1f".format(Locale.US, it) } ?: "--",
                )
            )
            TrackCanvas(samples = state.samples, modifier = Modifier.fillMaxWidth().height(260.dp))
            val series = listOf(
                ChartSeries("Speed (km/h)", Color.Cyan, state.samples.map { it.speedKmh }),
                ChartSeries("Voltage (V)", Color.Green, state.samples.map { it.voltageV }),
                ChartSeries("Current (A)", Color.Yellow, state.samples.map { it.currentA }),
            )
            TelemetryChart(series, Modifier.fillMaxWidth().height(240.dp))
            ChartLegend(series)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCsv) { Text("Export CSV") }
                Button(onClick = onGpx) { Text("Export GPX") }
            }
            OutlinedButton(onClick = onDelete) { Text("Delete trip") }
        }
    }
}

private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

internal fun formatDuration(seconds: Long): String =
    "%d:%02d:%02d".format(Locale.US, seconds / 3600, (seconds / 60) % 60, seconds % 60)
