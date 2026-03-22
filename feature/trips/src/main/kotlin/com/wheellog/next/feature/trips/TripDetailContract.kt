package com.wheellog.next.feature.trips

import com.wheellog.next.domain.model.Trip

/** User intents for the trip detail screen. */
sealed interface TripDetailIntent {
    data class LoadTrip(val tripId: Long) : TripDetailIntent
    data object ExportCsv : TripDetailIntent
}

/** UI state for the trip detail screen. */
data class TripDetailState(
    val isLoading: Boolean = true,
    val trip: Trip? = null,
)

/** One-shot side effects for the trip detail screen. */
sealed interface TripDetailEffect {
    data class ShowError(val message: String) : TripDetailEffect
    data class ShareCsv(val csvContent: String, val fileName: String) : TripDetailEffect
}
