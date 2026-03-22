package com.wheellog.next.feature.trips

import androidx.lifecycle.viewModelScope
import com.wheellog.next.core.ui.mvi.ComposeViewModel
import com.wheellog.next.domain.usecase.DeleteTripUseCase
import com.wheellog.next.domain.usecase.ExportTripsUseCase
import com.wheellog.next.domain.usecase.ObserveTripsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TripsViewModel @Inject constructor(
    private val observeTrips: ObserveTripsUseCase,
    private val deleteTrip: DeleteTripUseCase,
    private val exportTrips: ExportTripsUseCase,
) : ComposeViewModel<TripsIntent, TripsState, TripsEffect>(TripsState()) {

    init {
        handleIntent(TripsIntent.LoadTrips)
    }

    override fun handleIntent(intent: TripsIntent) {
        when (intent) {
            TripsIntent.LoadTrips -> loadTrips()
            is TripsIntent.DeleteTrip -> performDelete(intent.tripId)
            TripsIntent.ExportAllTrips -> performExport()
        }
    }

    private fun loadTrips() {
        observeTrips()
            .onEach { trips ->
                updateState { copy(isLoading = false, trips = trips) }
            }
            .catch { e ->
                updateState { copy(isLoading = false) }
                emitEffect(TripsEffect.ShowError(e.message ?: "Failed to load trips"))
            }
            .launchIn(viewModelScope)
    }

    private fun performDelete(tripId: Long) {
        viewModelScope.launch {
            try {
                deleteTrip(tripId)
                emitEffect(TripsEffect.TripDeleted)
            } catch (e: Exception) {
                emitEffect(TripsEffect.ShowError(e.message ?: "Failed to delete trip"))
            }
        }
    }

    private fun performExport() {
        viewModelScope.launch {
            try {
                val csv = exportTrips(state.value.trips)
                emitEffect(TripsEffect.ShareCsv(csv))
            } catch (e: Exception) {
                emitEffect(TripsEffect.ShowError(e.message ?: "Failed to export trips"))
            }
        }
    }
}
