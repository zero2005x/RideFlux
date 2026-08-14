/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.trips

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rideflux.domain.ride.Trip
import com.rideflux.domain.ride.TripRepository
import com.rideflux.domain.ride.TripSample
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TripHistoryViewModel @Inject constructor(
    private val repository: TripRepository,
) : ViewModel() {
    val trips: StateFlow<List<Trip>> = repository.observeTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun clearAll() = viewModelScope.launch(Dispatchers.IO) { repository.clearAll() }
}

data class TripDetailUiState(
    val trip: Trip? = null,
    val samples: List<TripSample> = emptyList(),
)

@HiltViewModel
class TripDetailViewModel @Inject constructor(
    private val repository: TripRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val tripId: Long = requireNotNull(savedStateHandle[ARG_TRIP_ID])

    val uiState: StateFlow<TripDetailUiState> = combine(
        repository.observeTrip(tripId),
        repository.observeSamples(tripId),
    ) { trip, samples -> TripDetailUiState(trip, samples) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), TripDetailUiState())

    fun delete(onDeleted: () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteTrip(tripId)
        launch(Dispatchers.Main) { onDeleted() }
    }

    fun exportCsv(resolver: ContentResolver, uri: Uri) = export(resolver, uri, TripExporter::writeCsv)
    fun exportGpx(resolver: ContentResolver, uri: Uri) = export(resolver, uri, TripExporter::writeGpx)

    private fun export(
        resolver: ContentResolver,
        uri: Uri,
        writer: (ContentResolver, Uri, Trip, List<TripSample>) -> Unit,
    ) = viewModelScope.launch(Dispatchers.IO) {
        uiState.value.trip?.let { writer(resolver, uri, it, uiState.value.samples) }
    }

    companion object { const val ARG_TRIP_ID = "tripId" }
}
