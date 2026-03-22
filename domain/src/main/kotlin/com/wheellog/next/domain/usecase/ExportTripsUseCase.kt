package com.wheellog.next.domain.usecase

import com.wheellog.next.domain.model.Trip
import com.wheellog.next.domain.repository.TripRepository

/** Export all trips as CSV string. */
class ExportTripsUseCase(
    private val repository: TripRepository,
) {
    suspend operator fun invoke(trips: List<Trip>): String = buildString {
        appendLine("id,start_time,end_time,duration_ms,distance_km,max_speed_kmh,avg_speed_kmh,device_address,wheel_type")
        for (trip in trips) {
            appendLine(
                "${trip.id},${trip.startTimeMillis},${trip.endTimeMillis}," +
                    "${trip.durationMillis},${trip.distanceKm},${trip.maxSpeedKmh}," +
                    "${trip.avgSpeedKmh},${trip.deviceAddress},${trip.wheelType}",
            )
        }
    }
}
