/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.recording

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import com.rideflux.core.location.TripLocationSource
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.ride.TripRepository
import com.rideflux.domain.wheel.WheelFamily
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives [RecordingService] through its Android entry points with the wheel, trip and location
 * sources replaced by stand-ins, so the start / stop protocol and the foreground handling can be
 * checked without a wheel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RecordingServiceTest {
    private lateinit var app: Application
    private lateinit var service: RecordingService
    private lateinit var wheels: WheelRepository
    private lateinit var locations: TripLocationSource

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        wheels = mockk()
        locations = mockk { every { hasPermission() } returns true }

        val created = RecordingService()
        // Hilt's generated base class injects on onCreate(); there is no Hilt graph in this
        // test, so mark it done and provide the dependencies by hand.
        created.javaClass.superclass.getDeclaredField("injected")
            .apply { isAccessible = true }
            .setBoolean(created, true)
        created.wheelRepository = wheels
        created.tripRepository = mockk<TripRepository>(relaxed = true)
        created.locationSource = locations
        ServiceController.of(created, null).create()
        service = created
    }

    @After
    fun tearDown() {
        service.onDestroy()
        unmockkAll()
    }

    private fun start(address: String? = ADDRESS, family: String? = WheelFamily.K.name): Int {
        val intent = Intent(app, RecordingService::class.java).setAction("com.rideflux.app.recording.START")
        if (address != null) intent.putExtra("address", address)
        if (family != null) intent.putExtra("family", family)
        return service.onStartCommand(intent, 0, 1)
    }

    private fun stopIntent() =
        Intent(app, RecordingService::class.java).setAction("com.rideflux.app.recording.STOP")

    private fun awaitTrue(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("$what not observed within ${timeoutMs}ms")
    }

    // ---- Lifecycle ----------------------------------------------------

    @Test
    fun registersALowImportanceChannelAndCannotBeBound() {
        val notifications = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = notifications.getNotificationChannel("ride_recording")

        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(service.onBind(Intent()))
    }

    // ---- Start --------------------------------------------------------

    @Test
    fun startPostsTheForegroundNotificationAndRecordsFromTheRequestedWheel() {
        coEvery { wheels.connect(any(), any()) } throws IOException("wheel unreachable")

        assertEquals(Service.START_NOT_STICKY, start())

        assertNotNull(shadowOf(service).lastForegroundNotification)
        coVerify(timeout = 10_000) { wheels.connect(ADDRESS, WheelFamily.K) }
        // A recording that ends for any reason must release the service.
        awaitTrue("the service to stop itself") { shadowOf(service).isStoppedBySelf }
    }

    @Test
    fun startWithoutLocationPermissionStillRecordsTelemetry() {
        every { locations.hasPermission() } returns false
        coEvery { wheels.connect(any(), any()) } throws IOException("wheel unreachable")

        assertEquals(Service.START_NOT_STICKY, start())

        assertNotNull(shadowOf(service).lastForegroundNotification)
        coVerify(timeout = 10_000) { wheels.connect(ADDRESS, WheelFamily.K) }
    }

    // The two tests below start one service each on purpose: a second start is ignored while the
    // first recording job is still finishing, so sharing a service would make them race.
    @Test
    fun anUnknownFamilyHintFallsBackToInference() {
        coEvery { wheels.connect(any(), any()) } throws IOException("wheel unreachable")

        start(family = "NOT_A_FAMILY")

        coVerify(timeout = 10_000) { wheels.connect(ADDRESS, null) }
    }

    @Test
    fun aStartWithoutAFamilyHintFallsBackToInference() {
        coEvery { wheels.connect(any(), any()) } throws IOException("wheel unreachable")

        start(family = null)

        coVerify(timeout = 10_000) { wheels.connect(ADDRESS, null) }
    }

    @Test
    fun aStartWithoutAnAddressIsIgnored() {
        assertEquals(Service.START_NOT_STICKY, start(address = null))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))

        assertNull(shadowOf(service).lastForegroundNotification)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun aForegroundServiceThatCannotStartEndsTheServiceWithoutRecording() {
        every { locations.hasPermission() } throws SecurityException("permission revoked")

        assertEquals(Service.START_NOT_STICKY, start())

        assertTrue(shadowOf(service).isStoppedBySelf)
        coVerify(exactly = 0) { wheels.connect(any(), any()) }
    }

    @Test
    fun aSecondStartWhileRecordingDoesNotOpenAnotherSession() {
        val cancelled = AtomicBoolean(false)
        coEvery { wheels.connect(any(), any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }

        start()
        coVerify(timeout = 10_000) { wheels.connect(ADDRESS, WheelFamily.K) }
        start()
        service.onDestroy() // cancels the running session

        awaitTrue("the session to be cancelled") { cancelled.get() }
        coVerify(exactly = 1) { wheels.connect(any(), any()) }
    }

    // ---- Stop ---------------------------------------------------------

    @Test
    fun stopBeforeAnythingIsRecordingStopsTheService() {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stopIntent(), 0, 1))

        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun stopWhileRecordingCancelsTheSessionAndThenStopsTheService() {
        val cancelled = AtomicBoolean(false)
        coEvery { wheels.connect(any(), any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }
        start()
        coVerify(timeout = 10_000) { wheels.connect(ADDRESS, WheelFamily.K) }
        assertFalse(shadowOf(service).isStoppedBySelf)

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stopIntent(), 0, 1))

        awaitTrue("the session to be cancelled") { cancelled.get() }
        awaitTrue("the service to stop itself") { shadowOf(service).isStoppedBySelf }
    }

    // ---- Companion API ------------------------------------------------

    @Test
    fun startAndStopSendTheIntentsTheServiceExpects() {
        RecordingService.start(app, ADDRESS, WheelFamily.G)
        val started = shadowOf(app).nextStartedService
        assertEquals("com.rideflux.app.recording.START", started.action)
        assertEquals(RecordingService::class.java.name, started.component?.className)
        assertEquals(ADDRESS, started.getStringExtra("address"))
        assertEquals("G", started.getStringExtra("family"))

        RecordingService.start(app, ADDRESS, null)
        assertNull(shadowOf(app).nextStartedService.getStringExtra("family"))

        RecordingService.stop(app)
        assertEquals("com.rideflux.app.recording.STOP", shadowOf(app).nextStartedService.action)
    }

    @Test
    fun aStartTheSystemForbidsIsLoggedNotThrown() {
        val illegalState = mockk<Context>(relaxed = true) {
            every { startForegroundService(any()) } throws IllegalStateException("background start not allowed")
        }
        val denied = mockk<Context>(relaxed = true) {
            every { startForegroundService(any()) } throws SecurityException("permission missing")
        }

        RecordingService.start(illegalState, ADDRESS, WheelFamily.G)
        RecordingService.start(denied, ADDRESS, WheelFamily.G)

        verify(exactly = 1) { illegalState.startForegroundService(any()) }
        verify(exactly = 1) { denied.startForegroundService(any()) }
    }

    @Test
    fun reportsWhetherFineLocationIsGranted() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertFalse(RecordingService.hasLocationPermission(app))

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        assertTrue(RecordingService.hasLocationPermission(app))
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
