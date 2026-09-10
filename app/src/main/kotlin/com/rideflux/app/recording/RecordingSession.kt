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
) {
    suspend fun record(address: String, family: WheelFamily?) = supervisorScope {
        var connection: WheelConnection? = null
        var locationJob: Job? = null
        var trip: Trip? = null
        val statistics = TripStatisticsAccumulator()
        val latestLocation = MutableStateFlow<TripLocation?>(null)
        var locationGranted = false
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
            var idleSeconds = 0
            var reachedActiveState = false
            while (true) {
                val connectionState = connection.state.value
                if (connectionState is ConnectionState.Failed ||
                    (connectionState == ConnectionState.Disconnected && reachedActiveState)) break
                if (connectionState != ConnectionState.Disconnected) reachedActiveState = true
                // connect() can return before its asynchronous startup leaves Disconnected.
                if (connectionState != ConnectionState.Ready) {
                    delay(SAMPLE_INTERVAL_MILLIS)
                    continue
                }
                val now = clock()
                val telemetry = connection.telemetry.value
                // A stalled GATT link must not keep adding its last speed to trip distance.
                if (telemetry.timestampMillis <= 0L ||
                    now - telemetry.timestampMillis !in 0L..TELEMETRY_MAX_AGE_MILLIS) break
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
                idleSeconds = if ((telemetry.speedKmh?.let(::abs) ?: 0f) < 1f) idleSeconds + 1 else 0
                if (idleSeconds >= IDLE_STOP_SECONDS) break
                delay(SAMPLE_INTERVAL_MILLIS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onError(error)
        } finally {
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

    private companion object {
        const val SAMPLE_INTERVAL_MILLIS = 1_000L
        const val TELEMETRY_MAX_AGE_MILLIS = 3_000L
        const val LOCATION_MAX_AGE_MILLIS = 10_000L
        const val IDLE_STOP_SECONDS = 120
        const val LIVE_SAMPLE_LIMIT = 10_800
    }
}
