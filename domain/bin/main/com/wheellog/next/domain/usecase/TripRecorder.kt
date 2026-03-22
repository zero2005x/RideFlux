package com.wheellog.next.domain.usecase

import com.wheellog.next.domain.model.TelemetryState
import com.wheellog.next.domain.model.Trip
import com.wheellog.next.domain.model.WheelType
import com.wheellog.next.domain.repository.TripRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stateful trip recorder — accumulates telemetry during an active ride
 * and saves the final Trip to [TripRepository] on stop.
 *
 * Must be Singleton-scoped (provided via Hilt @Singleton).
 */
class TripRecorder(
    private val tripRepository: TripRepository,
) {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var startTimeMillis = 0L
    private var initialTripDistanceKm = 0f
    private var lastTripDistanceKm = 0f
    private var maxSpeedKmh = 0f
    private var speedSum = 0.0
    private var speedCount = 0L
    private var deviceAddress = ""
    private var wheelType = WheelType.UNKNOWN

    /**
     * Begin recording a new trip. [currentTripDistance] is the wheel's
     * trip-odometer reading at the moment recording starts.
     */
    fun start(
        deviceAddress: String = "",
        wheelType: WheelType = WheelType.UNKNOWN,
        currentTripDistance: Float = 0f,
    ) {
        startTimeMillis = System.currentTimeMillis()
        initialTripDistanceKm = currentTripDistance
        lastTripDistanceKm = currentTripDistance
        maxSpeedKmh = 0f
        speedSum = 0.0
        speedCount = 0L
        this.deviceAddress = deviceAddress
        this.wheelType = wheelType
        _isRecording.value = true
    }

    /** Feed live telemetry to update trip stats. No-op if not recording. */
    fun update(telemetry: TelemetryState) {
        if (!_isRecording.value) return
        if (telemetry.speedKmh > maxSpeedKmh) maxSpeedKmh = telemetry.speedKmh
        lastTripDistanceKm = telemetry.tripDistanceKm
        speedSum += telemetry.speedKmh
        speedCount++
    }

    /** Stop recording, save the trip, and return it. Returns null if not recording. */
    suspend fun stop(): Trip? {
        if (!_isRecording.value) return null
        _isRecording.value = false

        val endTimeMillis = System.currentTimeMillis()
        val distanceKm = lastTripDistanceKm - initialTripDistanceKm
        val avgSpeedKmh = if (speedCount > 0) (speedSum / speedCount).toFloat() else 0f

        val trip = Trip(
            id = 0,
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis,
            distanceKm = distanceKm.coerceAtLeast(0f),
            maxSpeedKmh = maxSpeedKmh,
            avgSpeedKmh = avgSpeedKmh,
            deviceAddress = deviceAddress,
            wheelType = wheelType,
        )
        tripRepository.saveTrip(trip)
        return trip
    }
}
