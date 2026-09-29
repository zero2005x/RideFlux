/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.app.Application
import android.bluetooth.BluetoothDevice
import com.rideflux.data.bridge.BridgePairingToken
import com.rideflux.data.bridge.BridgeServer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [NativeBleBridgePublisher] is a thin adapter over [BridgeServer]. The server's own behaviour is
 * covered elsewhere; here the server methods are recorded and the callbacks the publisher hands the
 * server are invoked, so the state reporting and the authorization request mapping are checked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NativeBleBridgePublisherTest {
    private val states = mutableListOf<GlassesLinkState>()
    private val requests = mutableListOf<GlassesAuthorizationRequest>()
    private val dismissed = mutableListOf<String>()
    private lateinit var publisher: NativeBleBridgePublisher

    @Before
    fun setUp() {
        mockkConstructor(BridgeServer::class)
        every { anyConstructed<BridgeServer>().open() } returns true
        every { anyConstructed<BridgeServer>().stop() } just Runs
        every { anyConstructed<BridgeServer>().attachSource(any(), any()) } just Runs
        every { anyConstructed<BridgeServer>().setAdvertiseMode(any()) } returns true
        every { anyConstructed<BridgeServer>().approvePeer(any<String>()) } returns true
        every { anyConstructed<BridgeServer>().rejectPeer(any<String>()) } returns false
        publisher = NativeBleBridgePublisher(
            RuntimeEnvironment.getApplication(),
            onState = states::add,
            onAuthorizationRequested = requests::add,
            onAuthorizationDismissed = dismissed::add,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> serverCallback(name: String): T {
        val server = NativeBleBridgePublisher::class.java.getDeclaredField("server")
            .apply { isAccessible = true }
            .get(publisher)
        return BridgeServer::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(server) as T
    }

    private fun device(address: String?) = mockk<BluetoothDevice> { every { this@mockk.address } returns address }

    @Test
    fun openReportsStartingThenReadyOrError() = runBlocking {
        assertTrue(publisher.open())
        assertEquals(listOf(GlassesLinkState.STARTING, GlassesLinkState.READY), states)

        states.clear()
        every { anyConstructed<BridgeServer>().open() } returns false
        assertFalse(publisher.open())
        assertEquals(listOf(GlassesLinkState.STARTING, GlassesLinkState.ERROR), states)
    }

    @Test
    fun stopAndSourceAndPeerDecisionsAreDelegatedToTheServer() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            publisher.attachSource(scope, emptyFlow())
            verify(exactly = 1) { anyConstructed<BridgeServer>().attachSource(scope, any()) }
        } finally {
            scope.cancel()
        }
        assertTrue(publisher.approvePeer("AA:BB:CC:DD:EE:FF"))
        assertFalse(publisher.rejectPeer("AA:BB:CC:DD:EE:FF"))
        verify { anyConstructed<BridgeServer>().approvePeer("AA:BB:CC:DD:EE:FF") }
        verify { anyConstructed<BridgeServer>().rejectPeer("AA:BB:CC:DD:EE:FF") }

        publisher.stop()
        assertEquals(listOf(GlassesLinkState.STOPPED), states)
        verify(exactly = 1) { anyConstructed<BridgeServer>().stop() }
    }

    @Test
    fun advertiseModeFailureIsReportedAsAnError() {
        publisher.setLowLatency(true)
        verify { anyConstructed<BridgeServer>().setAdvertiseMode(true) }
        assertTrue(states.isEmpty())

        every { anyConstructed<BridgeServer>().setAdvertiseMode(any()) } returns false
        publisher.setLowLatency(false)
        assertEquals(listOf(GlassesLinkState.ERROR), states)
    }

    @Test
    fun subscriberStateMapsToLinkState() {
        val onSubscriberState = serverCallback<(Boolean) -> Unit>("onSubscriberStateChanged")
        onSubscriberState(true)
        onSubscriberState(false)
        assertEquals(listOf(GlassesLinkState.CONNECTED, GlassesLinkState.READY), states)
    }

    @Test
    fun authorizationRequestsCarryTheTokenOrAnAddressDerivedCode() {
        val onRequested = serverCallback<(BluetoothDevice, ByteArray?) -> Unit>("onAuthorizationRequested")
        val token = ByteArray(8) { (it + 1).toByte() }

        onRequested(device("AA:BB:CC:DD:EE:FF"), token)
        onRequested(device("AA:BB:CC:DD:EE:FF"), null)
        onRequested(device(null), null)

        assertEquals(3, requests.size)
        assertEquals(BridgePairingToken.shortCode(token), requests[0].shortCode)
        assertFalse(requests[0].isLegacy)
        assertTrue(requests[0].token!!.contentEquals(token))
        assertEquals("EEFF", requests[1].shortCode)
        assertTrue(requests[1].isLegacy)
        assertNull(requests[1].token)
        assertEquals("", requests[2].deviceAddress)
        assertEquals("????", requests[2].shortCode)
    }

    @Test
    fun anApprovalTimeoutDismissesThePrompt() {
        val onTimedOut = serverCallback<(BluetoothDevice) -> Unit>("onAuthorizationTimedOut")
        onTimedOut(device("AA:BB:CC:DD:EE:FF"))
        onTimedOut(device(null))
        assertEquals(listOf("AA:BB:CC:DD:EE:FF", ""), dismissed)
    }
}
