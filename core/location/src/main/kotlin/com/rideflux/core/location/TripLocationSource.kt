/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class TripLocation(
    val timestampMillis: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double?,
)

interface TripLocationSource {
    fun hasPermission(): Boolean
    fun locations(): Flow<TripLocation>
}

class FusedTripLocationSource(
    private val context: Context,
) : TripLocationSource {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    override fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun locations(): Flow<TripLocation> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    trySend(
                        TripLocation(
                            timestampMillis = location.time,
                            latitudeDeg = location.latitude,
                            longitudeDeg = location.longitude,
                            altitudeM = if (location.hasAltitude()) location.altitude else null,
                        )
                    )
                }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, SAMPLE_INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(SAMPLE_INTERVAL_MILLIS)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            close()
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    companion object { const val SAMPLE_INTERVAL_MILLIS = 1_000L }
}
