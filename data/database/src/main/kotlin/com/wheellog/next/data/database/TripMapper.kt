package com.wheellog.next.data.database

import com.wheellog.next.data.database.entity.TripEntity
import com.wheellog.next.domain.model.Trip
import com.wheellog.next.domain.model.WheelType

/** Maps between [TripEntity] (Room) and [Trip] (domain). */
object TripMapper {

    fun toDomain(entity: TripEntity): Trip = Trip(
        id = entity.id,
        startTimeMillis = entity.startTimeMillis,
        endTimeMillis = entity.endTimeMillis,
        distanceKm = entity.distanceKm,
        maxSpeedKmh = entity.maxSpeedKmh,
        avgSpeedKmh = entity.avgSpeedKmh,
        deviceAddress = entity.deviceAddress,
        wheelType = try {
            WheelType.valueOf(entity.wheelType)
        } catch (_: IllegalArgumentException) {
            WheelType.UNKNOWN
        },
    )

    fun toEntity(trip: Trip): TripEntity = TripEntity(
        id = trip.id,
        startTimeMillis = trip.startTimeMillis,
        endTimeMillis = trip.endTimeMillis,
        distanceKm = trip.distanceKm,
        maxSpeedKmh = trip.maxSpeedKmh,
        avgSpeedKmh = trip.avgSpeedKmh,
        deviceAddress = trip.deviceAddress,
        wheelType = trip.wheelType.name,
    )
}
