package com.wheellog.next.feature.trips

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.wheellog.next.core.ui.mvi.ComposeViewModel
import com.wheellog.next.domain.usecase.GetTripByIdUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class TripDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getTripById: GetTripByIdUseCase,
) : ComposeViewModel<TripDetailIntent, TripDetailState, TripDetailEffect>(TripDetailState()) {

    private val tripId: Long = checkNotNull(savedStateHandle["tripId"])

    init {
        handleIntent(TripDetailIntent.LoadTrip(tripId))
    }

    override fun handleIntent(intent: TripDetailIntent) {
        when (intent) {
            is TripDetailIntent.LoadTrip -> loadTrip(intent.tripId)
            TripDetailIntent.ExportCsv -> exportCsv()
        }
    }

    private fun loadTrip(id: Long) {
        viewModelScope.launch {
            try {
                val trip = getTripById(id)
                updateState { copy(isLoading = false, trip = trip) }
                if (trip == null) {
                    emitEffect(TripDetailEffect.ShowError("Trip not found"))
                }
            } catch (e: Exception) {
                updateState { copy(isLoading = false) }
                emitEffect(TripDetailEffect.ShowError(e.message ?: "Failed to load trip"))
            }
        }
    }

    private fun exportCsv() {
        val trip = state.value.trip ?: return
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
        val fileName = "trip_${dateFormat.format(Date(trip.startTimeMillis))}.csv"
        val csv = buildString {
            appendLine("field,value")
            appendLine("start_time,${trip.startTimeMillis}")
            appendLine("end_time,${trip.endTimeMillis}")
            appendLine("duration_ms,${trip.durationMillis}")
            appendLine("distance_km,${trip.distanceKm}")
            appendLine("max_speed_kmh,${trip.maxSpeedKmh}")
            appendLine("avg_speed_kmh,${trip.avgSpeedKmh}")
            appendLine("device_address,${trip.deviceAddress}")
            appendLine("wheel_type,${trip.wheelType}")
        }
        emitEffect(TripDetailEffect.ShareCsv(csvContent = csv, fileName = fileName))
    }
}
