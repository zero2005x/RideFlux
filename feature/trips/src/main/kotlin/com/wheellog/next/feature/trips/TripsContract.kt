package com.wheellog.next.feature.trips

import com.wheellog.next.domain.model.Trip

/** User intents for the trips screen. */
sealed interface TripsIntent {
    data object LoadTrips : TripsIntent
    data class DeleteTrip(val tripId: Long) : TripsIntent
    data object ExportAllTrips : TripsIntent
}

/** UI state for the trips screen. */
data class TripsState(
    val isLoading: Boolean = true,
    val trips: List<Trip> = emptyList(),
)

/** One-shot side effects for the trips screen. */
sealed interface TripsEffect {
    data class ShowError(val message: String) : TripsEffect
    data object TripDeleted : TripsEffect
    data class ShareCsv(val csvContent: String) : TripsEffect
}
