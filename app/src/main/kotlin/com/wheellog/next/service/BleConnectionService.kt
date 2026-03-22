package com.wheellog.next.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wheellog.next.MainActivity
import com.wheellog.next.domain.model.HudPayload
import com.wheellog.next.domain.model.TelemetryState
import com.wheellog.next.domain.repository.EucRepository
import com.wheellog.next.domain.repository.HudGatewayRepository
import com.wheellog.next.domain.repository.PreferencesRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the BLE connection alive when the app is in the background.
 * Updates the persistent notification with live telemetry data and
 * bridges telemetry to the HUD GATT server for Rokid glasses.
 */
@AndroidEntryPoint
class BleConnectionService : Service() {

    companion object {
        private const val CHANNEL_ID = "ble_connection_channel"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, BleConnectionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleConnectionService::class.java))
        }
    }

    @Inject lateinit var eucRepository: EucRepository
    @Inject lateinit var hudGatewayRepository: HudGatewayRepository
    @Inject lateinit var preferencesRepository: PreferencesRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(TelemetryState()))
        startHudAdvertising()
        observeConnectionState()
        observeTelemetry()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopHudAdvertising()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startHudAdvertising() {
        serviceScope.launch {
            hudGatewayRepository.startAdvertising()
        }
    }

    private fun stopHudAdvertising() {
        serviceScope.launch {
            hudGatewayRepository.stopAdvertising()
        }
    }

    private fun observeConnectionState() {
        eucRepository.observeConnectionState()
            .onEach { /* state changes observed for future use */ }
            .launchIn(serviceScope)
    }

    private fun observeTelemetry() {
        combine(
            eucRepository.observeTelemetry(),
            preferencesRepository.useMetricUnits(),
        ) { state, useMetric ->
            Pair(state, useMetric)
        }
            .onEach { (state, useMetric) ->
                // Update notification
                val notification = buildNotification(state)
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, notification)

                // Bridge telemetry to HUD GATT server
                hudGatewayRepository.pushPayload(
                    HudPayload(
                        speedKmh = state.speedKmh,
                        batteryPercent = state.batteryPercent,
                        temperatureC = state.temperatureC,
                        alertFlags = state.alertFlags,
                        useMetric = useMetric,
                    ),
                )
            }
            .launchIn(serviceScope)
    }

    private fun buildNotification(telemetry: TelemetryState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = "Speed: %.1f km/h  |  Battery: %d%%".format(
            telemetry.speedKmh,
            telemetry.batteryPercent,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WheelLog Next — Connected")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BLE Connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps BLE connection alive in background"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
