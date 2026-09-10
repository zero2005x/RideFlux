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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rideflux.app.MainActivity
import com.rideflux.app.R
import com.rideflux.core.location.TripLocationSource
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.ride.TripRepository
import com.rideflux.domain.ride.TripSample
import com.rideflux.domain.wheel.WheelFamily
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (recordingJob == null) stopSelf() else recordingJob?.cancel()
            return START_NOT_STICKY
        }
        val address = intent?.getStringExtra(EXTRA_ADDRESS) ?: return START_NOT_STICKY
        val family = intent.getStringExtra(EXTRA_FAMILY)?.let { runCatching { WheelFamily.valueOf(it) }.getOrNull() }
        try {
            startForegroundCompat(locationSource.hasPermission())
        } catch (error: RuntimeException) {
            // Permissions/foreground eligibility can change after the start request.
            Log.e(TAG, "Recording foreground service unavailable", error)
            stopSelf()
            return START_NOT_STICKY
        }
        if (recordingJob?.isCompleted != false) recordingJob = scope.launch { record(address, family) }
        return START_NOT_STICKY
    }

    private suspend fun record(address: String, family: WheelFamily?) {
        try {
            RecordingSession(wheelRepository, tripRepository, locationSource, _state,
                onError = { Log.e(TAG, "Recording failed", it) },
            ).record(address, family)
        } finally {
            stopSelf()
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
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(getString(R.string.notification_recording_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
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
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_recording),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val ACTION_START = "com.rideflux.app.recording.START"
        private const val ACTION_STOP = "com.rideflux.app.recording.STOP"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_FAMILY = "family"
        private const val CHANNEL_ID = "ride_recording"
        private const val NOTIFICATION_ID = 201

        private val _state = MutableStateFlow(RecordingUiState())
        val state: StateFlow<RecordingUiState> = _state.asStateFlow()

        fun start(context: Context, address: String, family: WheelFamily?) {
            if (_state.value.isRecording) return
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_FAMILY, family?.name)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Recording start is not currently allowed", error)
            } catch (error: SecurityException) {
                Log.w(TAG, "Recording permissions are unavailable", error)
            }
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
