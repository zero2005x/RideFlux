package com.wheellog.next.domain.usecase

import com.wheellog.next.domain.repository.TripRepository

/** Delete a recorded trip by ID. */
class DeleteTripUseCase(
    private val repository: TripRepository,
) {
    suspend operator fun invoke(tripId: Long) = repository.deleteTrip(tripId)
}
