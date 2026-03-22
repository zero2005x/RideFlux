package com.wheellog.next.core.testing

import com.wheellog.next.domain.model.Trip
import com.wheellog.next.domain.repository.TripRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Fake [TripRepository] for unit testing.
 */
class FakeTripRepository : TripRepository {

    private val trips = MutableStateFlow<List<Trip>>(emptyList())
    val savedTrips: List<Trip> get() = trips.value

    override fun observeTrips(): Flow<List<Trip>> =
        trips.map { it.sortedByDescending { t -> t.startTimeMillis } }

    override suspend fun getTripById(id: Long): Trip? =
        trips.value.find { it.id == id }

    override suspend fun saveTrip(trip: Trip): Long {
        val newId = (trips.value.maxOfOrNull { it.id } ?: 0) + 1
        trips.value = trips.value + trip.copy(id = newId)
        return newId
    }

    override suspend fun deleteTrip(id: Long) {
        trips.value = trips.value.filterNot { it.id == id }
    }
}
