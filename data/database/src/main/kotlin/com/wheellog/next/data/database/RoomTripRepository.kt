package com.wheellog.next.data.database

import com.wheellog.next.data.database.dao.TripDao
import com.wheellog.next.domain.model.Trip
import com.wheellog.next.domain.repository.TripRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed implementation of [TripRepository]. */
@Singleton
class RoomTripRepository @Inject constructor(
    private val tripDao: TripDao,
) : TripRepository {

    override fun observeTrips(): Flow<List<Trip>> =
        tripDao.observeAll().map { entities -> entities.map(TripMapper::toDomain) }

    override suspend fun getTripById(id: Long): Trip? =
        tripDao.getById(id)?.let(TripMapper::toDomain)

    override suspend fun saveTrip(trip: Trip): Long =
        tripDao.insert(TripMapper.toEntity(trip))

    override suspend fun deleteTrip(id: Long) =
        tripDao.deleteById(id)
}
