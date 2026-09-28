/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.screenshot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.rideflux.app.recording.RecordingUiState
import com.rideflux.app.recording.TripStatistics
import com.rideflux.app.ui.dashboard.DashboardAlert
import com.rideflux.app.ui.dashboard.DashboardScreen
import com.rideflux.app.ui.dashboard.DashboardUiState
import com.rideflux.app.ui.dashboard.TelemetrySample
import com.rideflux.app.ui.dashboard.TimedAlert
import com.rideflux.app.ui.hud.HudScreen
import com.rideflux.app.ui.settings.SettingsScreen
import com.rideflux.app.ui.theme.RideFluxTheme
import com.rideflux.app.ui.trips.TripDetailScreen
import com.rideflux.app.ui.trips.TripDetailUiState
import com.rideflux.app.ui.trips.TripHistoryScreen
import com.rideflux.domain.alert.ThresholdAlert
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.ride.Trip
import com.rideflux.domain.ride.TripSample
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.telemetry.RideMode
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity
import com.rideflux.domain.ride.TripRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug-only harness that renders the real, stateless app screens with
 * representative sample data so Play Store screenshots can be captured
 * on an emulator without a wheel attached.
 *
 * Launch with:
 *   adb shell am start -n com.rideflux.app/.screenshot.StoreScreenshotActivity --es screen dashboard
 *
 * `screen` is one of: dashboard, history, detail, settings, hud.
 * Dashboard pages are reached by swiping the pager.
 */
@AndroidEntryPoint
class StoreScreenshotActivity : ComponentActivity() {
    @Inject lateinit var tripRepository: TripRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "dashboard"
        // Seed the (emulator-only) database so screens backed by Hilt
        // ViewModels, such as the dashboard's Trips page, show sample trips.
        val sample = SampleData(System.currentTimeMillis())
        runBlocking(Dispatchers.IO) {
            tripRepository.importTrips(
                sample.trips.map { it to sample.route(it.id) },
                replaceAll = true,
            )
        }
        setContent {
            RideFluxTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Screen(screen)
                }
            }
        }
    }
}

@Composable
private fun Screen(screen: String) {
    val sample = SampleData(System.currentTimeMillis())
    when (screen) {
        "history" -> TripHistoryScreen(
            trips = sample.trips,
            onNavigateUp = {},
            onOpenTrip = {},
            onClearAll = {},
        )
        "detail" -> TripDetailScreen(
            state = TripDetailUiState(trip = sample.trips.first(), samples = sample.route(tripId = 1L)),
            onNavigateUp = {},
            onCsv = {},
            onGpx = {},
            onDelete = {},
        )
        "settings" -> SettingsScreen(
            settings = AppSettings(),
            onNavigateUp = {},
            onOpenTripHistory = {},
            onSpeedLimit = {},
            onTemperatureLimit = {},
            onLowBattery = {},
            onPwmAlert = {},
            onAlertsEnabled = {},
            onUseMetric = {},
            onKeepScreenOn = {},
            onBridgeAutostart = {},
            onStandbyLowLatency = {},
        )
        "hud" -> HudScreen(uiState = sample.uiState, onExit = {})
        else -> DashboardScreen(
            uiState = sample.uiState,
            history = sample.history,
            events = sample.events,
            activeAlert = null,
            recordingState = sample.recording,
            locationPermissionGranted = true,
            bridgeActive = true,
            bridgeAvailable = true,
            onNavigateUp = {},
        )
    }
}

private class SampleData(private val now: Long) {
    private val address = "D4:3A:2C:5E:91:07"

    val uiState = DashboardUiState(
        connectionState = ConnectionState.Ready,
        identity = WheelIdentity(
            address = address,
            family = WheelFamily.V,
            modelName = "Sherman S",
            firmwareVersion = "4.2.18",
        ),
        speedKmh = 32.4f,
        voltageV = 94.6f,
        batteryPercent = 71f,
        batteryVoltageV = 94.6f,
        currentA = 18.2f,
        phaseCurrentA = 42.5f,
        pwmPercent = 46f,
        mosTemperatureC = 41f,
        motorTemperatureC = 48f,
        boardTemperatureC = 38f,
        batteryTemperatureC = 33f,
        totalDistanceMetres = 3_482_300L,
        tripDistanceMetres = 12_840,
        rideMode = RideMode(code = 1, label = "Medium"),
        headlightOn = true,
        faults = emptySet(),
        maxSpeedKmh = 48.7f,
        avgSpeedKmh = 24.3f,
        rideTimeSeconds = 1_920L,
    )

    /** Three minutes of 1 Hz history with realistic speed, sag and heating. */
    val history: List<TelemetrySample> = (0 until 180).map { i ->
        val t = i.toDouble()
        val speed = (27 + 8 * sin(t / 17) + 3 * sin(t / 5.3)).toFloat()
        val accel = (8 / 17.0 * cos(t / 17) + 3 / 5.3 * cos(t / 5.3)).toFloat()
        val current = (14 + accel * 9 + speed * 0.12f).coerceAtLeast(2f)
        val voltage = 96.2f - i * 0.006f - current * 0.055f
        TelemetrySample(
            timestampMillis = now - (180 - i) * 1_000L,
            speedKmh = speed,
            voltageV = voltage,
            currentA = current,
            batteryPercent = 72f - i / 180f,
            mosTemperatureC = (39.5 + 1.2 * sin(t / 23) + 0.25 * sin(t / 3.7)).toFloat(),
            powerW = voltage * current,
        )
    }

    val events = listOf(
        TimedAlert(now - 95_000L, DashboardAlert.Threshold(ThresholdAlert.PwmLoad(91.5f, 90f))),
        TimedAlert(now - 412_000L, DashboardAlert.Wheel(WheelAlert.TiltBack(now - 412_000L, 47.8f, 48f))),
        TimedAlert(now - 418_000L, DashboardAlert.Threshold(ThresholdAlert.Overspeed(46.2f, 45f))),
    )

    /**
     * A riverside-style route (~5 km across) built from a smooth parametric
     * curve, sampled with speed/battery/PWM values that match its bends.
     */
    fun route(tripId: Long, points: Int = 360): List<TripSample> {
        val baseLat = 25.0712
        val baseLon = 121.5095
        val start = now - points * 5_000L
        return (0 until points).map { i ->
            val u = i / (points - 1.0)
            val a = u * 2 * PI
            // Distorted ellipse with gentle bends: a closed loop that never
            // crosses itself, like a lap along a riverside path.
            val lat = baseLat + 0.011 * sin(a) + 0.0018 * sin(3 * a + 0.6)
            val lon = baseLon + 0.019 * cos(a) + 0.0025 * cos(2 * a + 1.1)
            val speed = (24 + 7 * sin(a * 3) + 3 * cos(a * 7) + 0.6 * sin(a * 13)).toFloat()
            val current = 10f + speed * 0.3f + (0.6 * sin(a * 11)).toFloat()
            TripSample(
                tripId = tripId,
                timestampMillis = start + i * 5_000L,
                speedKmh = speed,
                voltageV = 97.8f - u.toFloat() * 3.2f - current * 0.05f,
                currentA = current,
                batteryPercent = 84f - u.toFloat() * 13f,
                pwmPercent = 22f + speed * 0.9f,
                mosTemperatureC = (33 + 9 * (1 - cos(a / 2)) / 2 + 0.4 * sin(a * 5)).toFloat(),
                latitudeDeg = lat,
                longitudeDeg = lon,
                altitudeM = 12.0 + 4 * sin(a * 2),
            )
        }
    }

    val recording = RecordingUiState(
        isRecording = true,
        tripId = 1L,
        statistics = TripStatistics(
            distanceMetres = 12_840.0,
            durationSeconds = 1_920L,
            maxSpeedKmh = 48.7f,
            avgSpeedKmh = 24.3f,
            startBatteryPercent = 84f,
            endBatteryPercent = 71f,
            maxPwmPercent = 68f,
            maxMosTemperatureC = 42f,
        ),
        samples = route(tripId = 1L),
        locationPermissionGranted = true,
    )

    private val day = 86_400_000L

    val trips = listOf(
        trip(1, now - 2 * 3_600_000L, 12_840.0, 1_920, 48.7f, 24.3f, 84f, 71f, 68f, 42f),
        trip(2, now - 1 * day - 5 * 3_600_000L, 23_410.0, 3_540, 52.1f, 23.8f, 97f, 68f, 74f, 47f),
        trip(3, now - 2 * day - 3 * 3_600_000L, 8_120.0, 1_260, 41.3f, 23.2f, 68f, 59f, 55f, 39f),
        trip(4, now - 4 * day - 7 * 3_600_000L, 31_760.0, 4_980, 55.4f, 23.0f, 100f, 58f, 81f, 51f),
        trip(5, now - 6 * day - 2 * 3_600_000L, 15_290.0, 2_310, 46.0f, 23.8f, 92f, 75f, 63f, 44f),
        trip(6, now - 9 * day - 4 * 3_600_000L, 6_480.0, 1_080, 38.6f, 21.6f, 75f, 68f, 49f, 37f),
    )

    private fun trip(
        id: Long,
        start: Long,
        distance: Double,
        seconds: Long,
        max: Float,
        avg: Float,
        startBattery: Float,
        endBattery: Float,
        pwm: Float,
        mos: Float,
    ) = Trip(
        id = id,
        wheelAddress = address,
        wheelModel = "Sherman S",
        startedAtMillis = start,
        endedAtMillis = start + seconds * 1_000L,
        distanceMetres = distance,
        durationSeconds = seconds,
        maxSpeedKmh = max,
        avgSpeedKmh = avg,
        startBatteryPercent = startBattery,
        endBatteryPercent = endBattery,
        maxPwmPercent = pwm,
        maxMosTemperatureC = mos,
    )
}
