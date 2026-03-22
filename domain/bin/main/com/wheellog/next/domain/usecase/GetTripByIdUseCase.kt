package com.wheellog.next.domain.usecase

import com.wheellog.next.domain.model.Trip
import com.wheellog.next.domain.repository.TripRepository

/** Retrieve a single trip by ID. */
class GetTripByIdUseCase(
    private val repository: TripRepository,
) {
    suspend operator fun invoke(tripId: Long): Trip? = repository.getTripById(tripId)
}
