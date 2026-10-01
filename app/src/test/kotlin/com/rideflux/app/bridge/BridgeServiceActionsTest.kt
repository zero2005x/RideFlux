/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.rideflux.app.R
import com.rideflux.data.bridge.BridgePairingToken
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import com.rideflux.domain.wheel.WheelFamily
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * Drives [BridgeService] through its Android entry points with the BLE and Rokid transports
 * replaced by recorded stand-ins, so command handling, publisher retry/degrade logic and the
 * notifications can be checked without opening a GATT server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BridgeServiceActionsTest {
    private lateinit var app: Application
    private lateinit var service: BridgeService
    private lateinit var notifications: NotificationManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        notifications = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mockkConstructor(NativeBleBridgePublisher::class, RokidCxrBridgePublisher::class)
        stubBle(opens = listOf(true))
        stubRokid(opens = listOf(true))
        BridgeService.setHudVisible(true)
        BridgeService.setLinkMode(app, GlassesLinkMode.ANDROID_BLE)

        val created = BridgeService()
        // Hilt's generated base class injects on onCreate(); there is no Hilt graph in this
        // test, so mark it done and provide the two dependencies by hand.
        created.javaClass.superclass.getDeclaredField("injected")
            .apply { isAccessible = true }
            .setBoolean(created, true)
        created.wheelRepository = mockk<WheelRepository>()
        created.settingsRepository = mockk<SettingsRepository>().also {
            every { it.settings } returns MutableStateFlow(AppSettings())
        }
        ServiceController.of(created, null).create()
        service = created
    }

    @After
    fun tearDown() {
        service.onDestroy()
        unmockkAll()
    }

    private fun stubBle(opens: List<Boolean>) {
        coEvery { anyConstructed<NativeBleBridgePublisher>().open() } returnsMany opens
        every { anyConstructed<NativeBleBridgePublisher>().attachSource(any(), any()) } just Runs
        every { anyConstructed<NativeBleBridgePublisher>().stop() } just Runs
        every { anyConstructed<NativeBleBridgePublisher>().setLowLatency(any()) } just Runs
        every { anyConstructed<NativeBleBridgePublisher>().approvePeer(any()) } returns true
        every { anyConstructed<NativeBleBridgePublisher>().rejectPeer(any()) } returns true
    }

    private fun stubRokid(opens: List<Boolean>) {
        coEvery { anyConstructed<RokidCxrBridgePublisher>().open() } returnsMany opens
        every { anyConstructed<RokidCxrBridgePublisher>().attachSource(any(), any()) } just Runs
        every { anyConstructed<RokidCxrBridgePublisher>().stop() } just Runs
        every { anyConstructed<RokidCxrBridgePublisher>().setLowLatency(any()) } just Runs
    }

    private fun send(intent: Intent?) = service.onStartCommand(intent, 0, 1)

    private fun awaitTrue(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    private fun invoke(name: String, paramType: Class<*>, arg: Any?): Any? =
        BridgeService::class.java.getDeclaredMethod(name, paramType)
            .apply { isAccessible = true }
            .invoke(service, arg)

    private fun request(address: String = ADDRESS, token: ByteArray? = TOKEN) = GlassesAuthorizationRequest(
        deviceAddress = address,
        token = token,
        shortCode = "A1B2",
        isLegacy = token == null,
    )

    // ---------------------------------------------------------------- commands

    @Test
    fun startCommandOpensTheBlePublisherAndParksInStandby() {
        assertEquals(Service.START_STICKY, send(Intent(BridgeService.ACTION_START)))

        verify(timeout = 10_000) { anyConstructed<NativeBleBridgePublisher>().attachSource(any(), any()) }
        awaitTrue { BridgeService.state.value == BridgeState.STANDBY }
        assertEquals(BridgeState.STANDBY, BridgeService.state.value)
    }

    @Test
    fun nullAndUnknownIntentsAreHarmless() {
        assertEquals(Service.START_STICKY, send(null))
        assertEquals(Service.START_STICKY, send(Intent("com.rideflux.app.bridge.NOPE")))
    }

    @Test
    fun setAndClearTargetTrackTheActiveWheel() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        send(
            Intent(BridgeService.ACTION_SET_TARGET)
                .putExtra(BridgeService.EXTRA_MAC, "aa:bb:cc:dd:ee:ff")
                .putExtra(BridgeService.EXTRA_FAMILY, WheelFamily.K.name),
        )
        assertEquals("AA:BB:CC:DD:EE:FF", BridgeService.activeMac.value)
        assertEquals(BridgeState.ATTACHING, BridgeService.state.value)
        // The foreground notification tracks the state once the service is in the foreground.
        val posted = shadowOf(notifications).getNotification(FOREGROUND_NOTIFICATION_ID)
        assertNotNull(posted)
        assertEquals(
            app.getString(R.string.notification_bridge_attaching),
            posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )

        send(Intent(BridgeService.ACTION_SET_TARGET).putExtra(BridgeService.EXTRA_MAC, "not-a-mac"))
        assertEquals("AA:BB:CC:DD:EE:FF", BridgeService.activeMac.value)

        send(Intent(BridgeService.ACTION_CLEAR_TARGET))
        assertNull(BridgeService.activeMac.value)
    }

    @Test
    fun everyBridgeStateHasItsOwnNotificationText() {
        val expected = mapOf(
            BridgeState.STOPPED to R.string.notification_bridge_standby,
            BridgeState.STANDBY to R.string.notification_bridge_standby,
            BridgeState.ATTACHING to R.string.notification_bridge_attaching,
            BridgeState.RELAYING to R.string.notification_bridge_relaying,
            BridgeState.DEGRADED to R.string.notification_bridge_degraded,
        )
        for ((state, textRes) in expected) {
            val notification = invoke("buildNotification", BridgeState::class.java, state) as Notification
            assertEquals(
                state.name,
                app.getString(textRes),
                notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
            )
            assertEquals(
                app.getString(R.string.notification_bridge_title),
                notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
            )
        }
    }

    // ------------------------------------------------- peer authorization

    @Test
    fun approveActionAllowsThePeerRemembersItAndClearsThePrompt() {
        send(Intent(BridgeService.ACTION_START))
        awaitTrue { BridgeService.state.value == BridgeState.STANDBY }
        invoke("handleAuthorizationRequested", GlassesAuthorizationRequest::class.java, request())
        assertNotNull(BridgeService.pendingAuthorization.value)

        // An approve without an address changes nothing.
        send(Intent(BridgeService.ACTION_APPROVE_PEER))
        assertNotNull(BridgeService.pendingAuthorization.value)

        send(
            Intent(BridgeService.ACTION_APPROVE_PEER)
                .putExtra(BridgeService.EXTRA_AUTH_ADDRESS, ADDRESS)
                .putExtra(BridgeService.EXTRA_AUTH_TOKEN_HEX, BridgePairingToken.toHex(TOKEN))
                .putExtra(BridgeService.EXTRA_AUTH_SHORT_CODE, "A1B2"),
        )

        verify { anyConstructed<NativeBleBridgePublisher>().approvePeer(ADDRESS) }
        assertNull(BridgeService.pendingAuthorization.value)
        val remembered = ApprovedGlassesStore.getAll(app).single { it.mac == ADDRESS }
        assertEquals(BridgePairingToken.toHex(TOKEN), remembered.tokenHex)
        assertEquals("A1B2", remembered.shortCode)
    }

    @Test
    fun rejectActionDeniesThePeerAndClearsThePrompt() {
        send(Intent(BridgeService.ACTION_START))
        awaitTrue { BridgeService.state.value == BridgeState.STANDBY }
        invoke("handleAuthorizationRequested", GlassesAuthorizationRequest::class.java, request())

        send(Intent(BridgeService.ACTION_REJECT_PEER))
        assertNotNull(BridgeService.pendingAuthorization.value)

        // A reject for some other address must not dismiss this prompt.
        send(Intent(BridgeService.ACTION_REJECT_PEER).putExtra(BridgeService.EXTRA_AUTH_ADDRESS, "11:22:33:44:55:66"))
        assertNotNull(BridgeService.pendingAuthorization.value)

        send(Intent(BridgeService.ACTION_REJECT_PEER).putExtra(BridgeService.EXTRA_AUTH_ADDRESS, ADDRESS))
        verify { anyConstructed<NativeBleBridgePublisher>().rejectPeer(ADDRESS) }
        assertNull(BridgeService.pendingAuthorization.value)
        assertTrue(ApprovedGlassesStore.getAll(app).none { it.mac == ADDRESS })
    }

    @Test
    fun authorizationPromptPostsAnActionableNotificationWhenAllowed() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        invoke("handleAuthorizationRequested", GlassesAuthorizationRequest::class.java, request())

        val posted = shadowOf(notifications).getNotification(AUTH_NOTIFICATION_ID)
        assertNotNull(posted)
        assertEquals(2, posted.actions.size)
        assertEquals(app.getString(R.string.action_deny), posted.actions[0].title.toString())
        assertEquals(app.getString(R.string.action_allow), posted.actions[1].title.toString())
        assertNotNull(notifications.getNotificationChannel("rideflux_bridge_auth"))

        // Dismissing a different device leaves the prompt; dismissing this one clears both.
        invoke("handleAuthorizationDismissed", String::class.java, "11:22:33:44:55:66")
        assertNotNull(BridgeService.pendingAuthorization.value)
        assertNotNull(shadowOf(notifications).getNotification(AUTH_NOTIFICATION_ID))
        invoke("handleAuthorizationDismissed", String::class.java, ADDRESS)
        assertNull(BridgeService.pendingAuthorization.value)
        assertNull(shadowOf(notifications).getNotification(AUTH_NOTIFICATION_ID))
    }

    @Test
    fun authorizationPromptStillReachesTheAppWithoutNotificationPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        invoke("handleAuthorizationRequested", GlassesAuthorizationRequest::class.java, request(token = null))

        assertEquals(ADDRESS, BridgeService.pendingAuthorization.value!!.deviceAddress)
        assertTrue(BridgeService.pendingAuthorization.value!!.isLegacy)
        assertNull(shadowOf(notifications).getNotification(AUTH_NOTIFICATION_ID))
    }

    // ------------------------------------------------- publisher lifecycle

    @Test
    fun aFailedOpenIsRetriedUntilThePublisherComesUp() {
        stubBle(opens = listOf(false, true))
        send(Intent(BridgeService.ACTION_START))

        verify(timeout = 10_000) { anyConstructed<NativeBleBridgePublisher>().attachSource(any(), any()) }
        verify(atLeast = 1) { anyConstructed<NativeBleBridgePublisher>().stop() }
        awaitTrue { BridgeService.state.value == BridgeState.STANDBY }
    }

    @Test
    fun aFailingRokidLinkFallsBackToNativeBle() {
        BridgeService.setLinkMode(app, GlassesLinkMode.ROKID_CXR)
        assertEquals(GlassesLinkMode.ROKID_CXR, BridgeService.linkMode.value)
        stubRokid(opens = listOf(false))

        send(Intent(BridgeService.ACTION_START))

        awaitTrue { BridgeService.linkMode.value == GlassesLinkMode.ANDROID_BLE }
        verify(timeout = 10_000) { anyConstructed<NativeBleBridgePublisher>().attachSource(any(), any()) }
        assertEquals(GlassesLinkMode.ANDROID_BLE, GlassesLinkPreferences.read(app))
    }

    @Test
    fun switchingLinkModeReplacesThePublisher() {
        send(Intent(BridgeService.ACTION_START))
        verify(timeout = 10_000) { anyConstructed<NativeBleBridgePublisher>().attachSource(any(), any()) }
        awaitTrue { BridgeService.state.value == BridgeState.STANDBY }

        // Unknown and unchanged modes are ignored.
        send(Intent(BridgeService.ACTION_SET_LINK_MODE).putExtra(BridgeService.EXTRA_LINK_MODE, "bogus"))
        send(Intent(BridgeService.ACTION_SET_LINK_MODE).putExtra(BridgeService.EXTRA_LINK_MODE, "ANDROID_BLE"))
        verify(exactly = 0) { anyConstructed<RokidCxrBridgePublisher>().attachSource(any(), any()) }

        send(Intent(BridgeService.ACTION_SET_LINK_MODE).putExtra(BridgeService.EXTRA_LINK_MODE, "ROKID_CXR"))

        assertEquals(GlassesLinkMode.ROKID_CXR, BridgeService.linkMode.value)
        assertEquals(GlassesLinkMode.ROKID_CXR, GlassesLinkPreferences.read(app))
        verify { anyConstructed<NativeBleBridgePublisher>().stop() }
        verify(timeout = 10_000) { anyConstructed<RokidCxrBridgePublisher>().attachSource(any(), any()) }
    }

    // ------------------------------------------------- Bluetooth restarts

    private fun broadcastBluetoothState(state: Int) {
        app.sendBroadcast(
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, state),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun bluetoothReceiverRegistered(): Boolean =
        shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(BluetoothAdapter.ACTION_STATE_CHANGED) }

    @Test
    fun aBluetoothRestartRebuildsThePublisher() {
        stubBle(opens = listOf(true, true))
        send(Intent(BridgeService.ACTION_START))
        verify(timeout = 10_000, exactly = 1) { anyConstructed<NativeBleBridgePublisher>().attachSource(any(), any()) }
        awaitTrue { BridgeService.state.value == BridgeState.STANDBY }

        // The GATT server dies with the radio, so the publisher is released at once.
        broadcastBluetoothState(BluetoothAdapter.STATE_TURNING_OFF)
        verify(exactly = 1) { anyConstructed<NativeBleBridgePublisher>().stop() }
        assertEquals(BridgeState.DEGRADED, BridgeService.state.value)
        assertEquals(GlassesLinkState.STOPPED, BridgeService.linkState.value)

        // Half-way states change nothing; only a fully-on adapter brings a fresh server up.
        broadcastBluetoothState(BluetoothAdapter.STATE_TURNING_ON)
        verify(exactly = 1) { anyConstructed<NativeBleBridgePublisher>().attachSource(any(), any()) }
        broadcastBluetoothState(BluetoothAdapter.STATE_ON)
        verify(timeout = 10_000, exactly = 2) { anyConstructed<NativeBleBridgePublisher>().attachSource(any(), any()) }
        awaitTrue { BridgeService.state.value == BridgeState.STANDBY }
    }

    @Test
    fun theWheelTargetSurvivesABluetoothRestart() {
        stubBle(opens = listOf(true, true))
        send(
            Intent(BridgeService.ACTION_SET_TARGET)
                .putExtra(BridgeService.EXTRA_MAC, ADDRESS)
                .putExtra(BridgeService.EXTRA_FAMILY, WheelFamily.G.name),
        )
        verify(timeout = 10_000, exactly = 1) { anyConstructed<NativeBleBridgePublisher>().attachSource(any(), any()) }

        broadcastBluetoothState(BluetoothAdapter.STATE_OFF)
        broadcastBluetoothState(BluetoothAdapter.STATE_ON)

        verify(timeout = 10_000, exactly = 2) { anyConstructed<NativeBleBridgePublisher>().attachSource(any(), any()) }
        assertEquals(ADDRESS, BridgeService.activeMac.value)
    }

    @Test
    fun bluetoothComingOnWhileThePublisherIsUpChangesNothing() {
        send(Intent(BridgeService.ACTION_START))
        verify(timeout = 10_000) { anyConstructed<NativeBleBridgePublisher>().attachSource(any(), any()) }

        broadcastBluetoothState(BluetoothAdapter.STATE_ON)

        verify(exactly = 1) { anyConstructed<NativeBleBridgePublisher>().attachSource(any(), any()) }
        verify(exactly = 0) { anyConstructed<NativeBleBridgePublisher>().stop() }
    }

    @Test
    fun aBluetoothShutdownDropsAnApprovalPromptTheRadioTookDown() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        send(Intent(BridgeService.ACTION_START))
        awaitTrue { BridgeService.state.value == BridgeState.STANDBY }
        invoke("handleAuthorizationRequested", GlassesAuthorizationRequest::class.java, request())
        assertNotNull(BridgeService.pendingAuthorization.value)
        assertNotNull(shadowOf(notifications).getNotification(AUTH_NOTIFICATION_ID))

        broadcastBluetoothState(BluetoothAdapter.STATE_OFF)

        assertNull(BridgeService.pendingAuthorization.value)
        assertNull(shadowOf(notifications).getNotification(AUTH_NOTIFICATION_ID))
    }

    @Test
    fun unrelatedBroadcastsAndUnknownStatesAreIgnored() {
        send(Intent(BridgeService.ACTION_START))
        verify(timeout = 10_000) { anyConstructed<NativeBleBridgePublisher>().attachSource(any(), any()) }

        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED)) // no state extra at all
        shadowOf(Looper.getMainLooper()).idle()
        broadcastBluetoothState(BluetoothAdapter.ERROR)

        verify(exactly = 0) { anyConstructed<NativeBleBridgePublisher>().stop() }
    }

    @Test
    fun theBluetoothReceiverLivesExactlyAsLongAsTheService() {
        assertTrue(bluetoothReceiverRegistered())

        service.onDestroy()

        assertFalse(bluetoothReceiverRegistered())
    }

    @Test
    fun adapterStatesMapToTheBridgesReaction() {
        assertEquals(BluetoothStateAction.RELEASE_PUBLISHER, bluetoothStateAction(BluetoothAdapter.STATE_TURNING_OFF))
        assertEquals(BluetoothStateAction.RELEASE_PUBLISHER, bluetoothStateAction(BluetoothAdapter.STATE_OFF))
        assertEquals(BluetoothStateAction.REOPEN_PUBLISHER, bluetoothStateAction(BluetoothAdapter.STATE_ON))
        assertEquals(BluetoothStateAction.IGNORE, bluetoothStateAction(BluetoothAdapter.STATE_TURNING_ON))
        assertEquals(BluetoothStateAction.IGNORE, bluetoothStateAction(BluetoothAdapter.ERROR))
    }

    // ------------------------------------------------- static entry points

    @Test
    fun companionCommandsStartTheServiceWithTheRightIntents() {
        val shadow = shadowOf(app)

        BridgeService.startStandby(app)
        assertEquals(BridgeService.ACTION_START, shadow.nextStartedService.action)

        BridgeService.setTarget(app, ADDRESS, WheelFamily.K)
        shadow.nextStartedService.let {
            assertEquals(BridgeService.ACTION_SET_TARGET, it.action)
            assertEquals(ADDRESS, it.getStringExtra(BridgeService.EXTRA_MAC))
            assertEquals("K", it.getStringExtra(BridgeService.EXTRA_FAMILY))
        }

        BridgeService.start(app, ADDRESS, null)
        shadow.nextStartedService.let {
            assertEquals(BridgeService.ACTION_SET_TARGET, it.action)
            assertFalse(it.hasExtra(BridgeService.EXTRA_FAMILY))
        }

        BridgeService.clearTarget(app)
        assertEquals(BridgeService.ACTION_CLEAR_TARGET, shadow.nextStartedService.action)

        val req = request()
        BridgeService.approveGlasses(app, req)
        shadow.nextStartedService.let {
            assertEquals(BridgeService.ACTION_APPROVE_PEER, it.action)
            assertEquals(ADDRESS, it.getStringExtra(BridgeService.EXTRA_AUTH_ADDRESS))
            assertEquals(BridgePairingToken.toHex(TOKEN), it.getStringExtra(BridgeService.EXTRA_AUTH_TOKEN_HEX))
            assertEquals("A1B2", it.getStringExtra(BridgeService.EXTRA_AUTH_SHORT_CODE))
            assertFalse(it.getBooleanExtra(BridgeService.EXTRA_AUTH_IS_LEGACY, true))
        }

        BridgeService.rejectGlasses(app, req)
        shadow.nextStartedService.let {
            assertEquals(BridgeService.ACTION_REJECT_PEER, it.action)
            assertEquals(ADDRESS, it.getStringExtra(BridgeService.EXTRA_AUTH_ADDRESS))
        }
        assertNull(BridgeService.pendingAuthorization.value)

        BridgeService.stop(app)
        assertNotNull(shadow.nextStoppedService)
    }

    @Test
    fun linkModeIsWrittenDirectlyWhileStoppedAndSentToTheServiceOtherwise() {
        val shadow = shadowOf(app)
        assertEquals(BridgeState.STOPPED, BridgeService.state.value)
        BridgeService.setLinkMode(app, GlassesLinkMode.ROKID_CXR)
        assertNull(shadow.nextStartedService)
        assertEquals(GlassesLinkMode.ROKID_CXR, BridgeService.linkMode.value)

        send(Intent(BridgeService.ACTION_SET_TARGET).putExtra(BridgeService.EXTRA_MAC, ADDRESS))
        BridgeService.setLinkMode(app, GlassesLinkMode.ANDROID_BLE)
        val started = shadow.nextStartedService
        assertEquals(BridgeService.ACTION_SET_LINK_MODE, started.action)
        assertEquals("ANDROID_BLE", started.getStringExtra(BridgeService.EXTRA_LINK_MODE))
    }

    @Test
    fun hudVisibilityToggles() {
        BridgeService.setHudVisible(true)
        BridgeService.toggleHudVisible()
        assertFalse(BridgeService.hudVisible.value)
        BridgeService.toggleHudVisible()
        assertTrue(BridgeService.hudVisible.value)
    }

    @Test
    fun syncingTheLinkModeReadsThePersistedChoice() {
        BridgeService.syncLinkMode(app)
        BridgeService.syncLinkMode(app) // once per process: the second call is a no-op
        assertNotNull(BridgeService.linkMode.value)
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
        val TOKEN = ByteArray(8) { (it + 1).toByte() }
        const val FOREGROUND_NOTIFICATION_ID = 7421
        const val AUTH_NOTIFICATION_ID = 7422
    }
}
