package com.wheellog.next.domain.model

/**
 * Domain model representing a recorded ride trip.
 * Mapped from [TripEntity] in the data layer.
 */
data class Trip(
    val id: Long,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val distanceKm: Float,
    val maxSpeedKmh: Float,
    val avgSpeedKmh: Float,
    val deviceAddress: String,
    val wheelType: WheelType,
) {
    /** Duration in milliseconds. */
    val durationMillis: Long get() = endTimeMillis - startTimeMillis
}
