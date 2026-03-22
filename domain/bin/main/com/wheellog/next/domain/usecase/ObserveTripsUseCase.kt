package com.wheellog.next.domain.usecase

import com.wheellog.next.domain.model.Trip
import com.wheellog.next.domain.repository.TripRepository
import kotlinx.coroutines.flow.Flow

/** Observe the list of recorded trips. */
class ObserveTripsUseCase(
    private val repository: TripRepository,
) {
    operator fun invoke(): Flow<List<Trip>> = repository.observeTrips()
}
