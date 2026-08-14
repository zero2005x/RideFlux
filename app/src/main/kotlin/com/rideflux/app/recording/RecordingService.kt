/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.recording

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rideflux.app.MainActivity
import com.rideflux.app.R
import com.rideflux.core.location.TripLocation
import com.rideflux.core.location.TripLocationSource
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.ride.Trip
import com.rideflux.domain.ride.TripRepository
import com.rideflux.domain.ride.TripSample
import com.rideflux.domain.wheel.WheelFamily
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs

data class RecordingUiState(
    val isRecording: Boolean = false,
    val tripId: Long? = null,
    val statistics: TripStatistics = TripStatistics(),
    val samples: List<TripSample> = emptyList(),
    val locationPermissionGranted: Boolean = false,
)

@AndroidEntryPoint
class RecordingService : Service() {
    @Inject lateinit var wheelRepository: WheelRepository
    @Inject lateinit var tripRepository: TripRepository
    @Inject lateinit var locationSource: TripLocationSource

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var recordingJob: Job? = null
    @Volatile private var latestLocation: TripLocation? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            recordingJob?.cancel()
            return START_NOT_STICKY
        }
        val address = intent?.getStringExtra(EXTRA_ADDRESS) ?: return START_NOT_STICKY
        val family = intent.getStringExtra(EXTRA_FAMILY)?.let { runCatching { WheelFamily.valueOf(it) }.getOrNull() }
        startForegroundCompat(locationSource.hasPermission())
        if (recordingJob?.isActive != true) recordingJob = scope.launch { record(address, family) }
        return START_NOT_STICKY
    }

    private suspend fun record(address: String, family: WheelFamily?) {
        val connection = try {
            wheelRepository.connect(address, family)
        } catch (_: Throwable) {
            stopSelf()
            return
        }
        val locationGranted = locationSource.hasPermission()
        val locationJob = if (locationGranted) scope.launch {
            locationSource.locations().collectLatest { latestLocation = it }
        } else null
        val startedAt = System.currentTimeMillis()
        val initialTrip = Trip(
            wheelAddress = address,
            wheelModel = connection.identity.value?.modelName,
            startedAtMillis = startedAt,
            startBatteryPercent = connection.telemetry.value.batteryPercent,
        )
        val tripId = tripRepository.createTrip(initialTrip)
        val statistics = TripStatisticsAccumulator()
        var persistedTrip = initialTrip.copy(id = tripId)
        var belowThresholdSeconds = 0
        _state.value = RecordingUiState(true, tripId, locationPermissionGranted = locationGranted)

        try {
            while (true) {
                val connectionState = connection.state.value
                if (connectionState == ConnectionState.Disconnected || connectionState is ConnectionState.Failed) break
                val now = System.currentTimeMillis()
                val telemetry = connection.telemetry.value
                val location = latestLocation?.takeIf { now - it.timestampMillis <= LOCATION_MAX_AGE_MILLIS }
                val sample = TripSample(
                    tripId = tripId,
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
                tripRepository.appendSample(sample)
                val snapshot = statistics.add(telemetry, now)
                _state.value = _state.value.copy(
                    statistics = snapshot,
                    samples = (_state.value.samples + sample).takeLast(LIVE_SAMPLE_LIMIT),
                )
                belowThresholdSeconds = if ((telemetry.speedKmh?.let(::abs) ?: 0f) < RIDE_THRESHOLD_KMH) {
                    belowThresholdSeconds + 1
                } else {
                    0
                }
                if (belowThresholdSeconds >= IDLE_STOP_SECONDS) break
                delay(SAMPLE_INTERVAL_MILLIS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                locationJob?.cancel()
                val finalStats = statistics.snapshot()
                persistedTrip = persistedTrip.copy(
                    endedAtMillis = System.currentTimeMillis(),
                    distanceMetres = finalStats.distanceMetres,
                    durationSeconds = finalStats.durationSeconds,
                    maxSpeedKmh = finalStats.maxSpeedKmh,
                    avgSpeedKmh = finalStats.avgSpeedKmh,
                    startBatteryPercent = finalStats.startBatteryPercent,
                    endBatteryPercent = finalStats.endBatteryPercent,
                    maxPwmPercent = finalStats.maxPwmPercent,
                    maxMosTemperatureC = finalStats.maxMosTemperatureC,
                )
                tripRepository.finishTrip(persistedTrip)
                runCatching { connection.close() }
                _state.value = RecordingUiState(locationPermissionGranted = locationGranted)
                stopSelf()
            }
        }
    }

    private fun startForegroundCompat(locationGranted: Boolean) {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("RideFlux is recording")
            .setContentText("Wheel telemetry is being saved locally")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (locationGranted) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Trip recording", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.rideflux.app.recording.START"
        private const val ACTION_STOP = "com.rideflux.app.recording.STOP"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_FAMILY = "family"
        private const val CHANNEL_ID = "ride_recording"
        private const val NOTIFICATION_ID = 201
        private const val SAMPLE_INTERVAL_MILLIS = 1_000L
        private const val LOCATION_MAX_AGE_MILLIS = 10_000L
        private const val RIDE_THRESHOLD_KMH = 1f
        private const val IDLE_STOP_SECONDS = 120
        private const val LIVE_SAMPLE_LIMIT = 10_800

        private val _state = MutableStateFlow(RecordingUiState())
        val state: StateFlow<RecordingUiState> = _state.asStateFlow()

        fun start(context: Context, address: String, family: WheelFamily?) {
            if (_state.value.isRecording) return
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_FAMILY, family?.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }

        fun hasLocationPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
