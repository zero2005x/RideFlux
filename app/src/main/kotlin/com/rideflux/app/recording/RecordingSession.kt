/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.recording

import com.rideflux.core.location.TripLocation
import com.rideflux.core.location.TripLocationSource
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.WheelConnection
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.ride.Trip
import com.rideflux.domain.ride.TripRepository
import com.rideflux.domain.ride.TripSample
import com.rideflux.domain.wheel.WheelFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Owns every resource from connection acquisition through database finalization. */
internal class RecordingSession(
    private val wheels: WheelRepository,
    private val trips: TripRepository,
    private val locations: TripLocationSource,
    private val state: MutableStateFlow<RecordingUiState>,
    private val onError: (Throwable) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onEvent: (String) -> Unit = {},
) {
    suspend fun record(address: String, family: WheelFamily?) = supervisorScope {
        var connection: WheelConnection? = null
        var locationJob: Job? = null
        var trip: Trip? = null
        val statistics = TripStatisticsAccumulator()
        val latestLocation = MutableStateFlow<TripLocation?>(null)
        var locationGranted = false
        var stopReason = "cancelled"
        try {
            connection = wheels.connect(address, family)
            locationGranted = locations.hasPermission()
            if (locationGranted) {
                locationJob = launch {
                    try {
                        locations.locations().collect { latestLocation.value = it }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        latestLocation.value = null
                        onError(error)
                    }
                }
            }
            val initial = Trip(
                wheelAddress = address,
                wheelModel = connection.identity.value?.modelName,
                startedAtMillis = clock(),
                startBatteryPercent = connection.telemetry.value.batteryPercent,
            )
            trip = initial.copy(id = trips.createTrip(initial))
            state.value = RecordingUiState(true, trip.id, locationPermissionGranted = locationGranted)
            event("started")
            var idleSeconds = 0
            var missingSince: Long? = null
            while (true) {
                val connectionState = connection.state.value
                // Failed handles cannot recover without a new connection coordinator.
                if (connectionState is ConnectionState.Failed) {
                    stopReason = "connection_failed_${connectionState.reason}"
                    break
                }
                val now = clock()
                val telemetry = connection.telemetry.value
                val fresh = connectionState == ConnectionState.Ready && telemetry.timestampMillis > 0L &&
                    now - telemetry.timestampMillis in 0L..TELEMETRY_MAX_AGE_MILLIS
                if (!fresh) {
                    val elapsed = elapsedClock()
                    val since = missingSince ?: elapsed.also {
                        missingSince = it
                        event("paused_${if (connectionState == ConnectionState.Ready) "stale_telemetry" else "connection_not_ready"}")
                    }
                    statistics.breakInterval()
                    idleSeconds = 0
                    if (elapsed - since >= SILENCE_GRACE_MILLIS) {
                        stopReason = "silence_timeout"
                        break
                    }
                    delay(SAMPLE_INTERVAL_MILLIS)
                    continue
                }
                if (missingSince != null) event("resumed")
                missingSince = null
                val location = latestLocation.value?.takeIf {
                    now - it.timestampMillis in 0L..LOCATION_MAX_AGE_MILLIS
                }
                val sample = TripSample(
                    tripId = trip.id,
                    timestampMillis = now,
                    speedKmh = telemetry.speedKmh?.let(::abs),
                    voltageV = telemetry.voltageV,
                    currentA = telemetry.currentA,
                    batteryPercent = telemetry.batteryPercent,
                    pwmPercent = telemetry.pwmPercent?.let(::abs),
                    mosTemperatureC = telemetry.mosTemperatureC,
                    latitudeDeg = location?.latitudeDeg,
                    longitudeDeg = location?.longitudeDeg,
                    altitudeM = location?.altitudeM,
                )
                trips.appendSample(sample)
                state.value = state.value.copy(
                    statistics = statistics.add(telemetry, now),
                    samples = (state.value.samples + sample).takeLast(LIVE_SAMPLE_LIMIT),
                )
                val speed = telemetry.speedKmh?.takeIf(Float::isFinite)?.let(::abs)
                idleSeconds = if (speed != null && speed < 1f) idleSeconds + 1 else 0
                if (idleSeconds >= IDLE_STOP_SECONDS) {
                    stopReason = "idle_timeout"
                    break
                }
                delay(SAMPLE_INTERVAL_MILLIS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            stopReason = "session_error"
            onError(error)
        } finally {
            event("ending_$stopReason")
            withContext(NonCancellable) {
                try {
                    locationJob?.cancelAndJoin()
                    trip?.let { persisted ->
                        val stats = statistics.snapshot()
                        trips.finishTrip(persisted.copy(
                            endedAtMillis = clock(),
                            distanceMetres = stats.distanceMetres,
                            durationSeconds = stats.durationSeconds,
                            maxSpeedKmh = stats.maxSpeedKmh,
                            avgSpeedKmh = stats.avgSpeedKmh,
                            startBatteryPercent = stats.startBatteryPercent ?: persisted.startBatteryPercent,
                            endBatteryPercent = stats.endBatteryPercent,
                            maxPwmPercent = stats.maxPwmPercent,
                            maxMosTemperatureC = stats.maxMosTemperatureC,
                        ))
                    }
                } catch (error: Exception) {
                    onError(error)
                } finally {
                    try {
                        connection?.close()
                    } catch (error: Exception) {
                        onError(error)
                    } finally {
                        state.value = RecordingUiState(locationPermissionGranted = locationGranted)
                    }
                }
            }
        }
    }

    private fun event(message: String) {
        // Diagnostics must never prevent trip finalization or resource release.
        runCatching { onEvent(message) }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MILLIS = 1_000L
        const val TELEMETRY_MAX_AGE_MILLIS = 3_000L
        const val SILENCE_GRACE_MILLIS = 30_000L
        const val LOCATION_MAX_AGE_MILLIS = 10_000L
        const val IDLE_STOP_SECONDS = 120
        const val LIVE_SAMPLE_LIMIT = 10_800
    }
}
