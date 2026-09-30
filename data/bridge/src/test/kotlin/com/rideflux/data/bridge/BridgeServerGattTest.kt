/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BridgeServerGattTest {
    private val peer = mockk<BluetoothDevice> {
        every { address } returns "AA:BB:CC:DD:EE:FF"
    }

    @Test
    fun registersServiceBeforeAdvertisingAndStopsCleanly() {
        val fixture = Fixture()
        val server = BridgeServer(fixture.context)
        assertTrue(server.open())
        assertEquals(listOf("addService", "advertise"), fixture.events)
        assertTrue(server.open()) // Idempotent; no duplicate GATT registration.
        verify(exactly = 1) { fixture.gatt.addService(any()) }
        server.stop()
        server.stop()
        verify(exactly = 1) { fixture.gatt.close() }
    }

    @Test
    fun failedServiceRegistrationClosesGattWithoutAdvertising() {
        val fixture = Fixture(serviceStatus = BluetoothGatt.GATT_FAILURE)
        val server = BridgeServer(fixture.context)
        assertFalse(server.open())
        assertEquals(listOf("addService"), fixture.events)
        verify(exactly = 1) { fixture.gatt.close() }
        verify(exactly = 0) {
            fixture.advertiser.startAdvertising(
                any<AdvertiseSettings>(), any<AdvertiseData>(), any<AdvertiseCallback>(),
            )
        }
    }

    @Test
    fun cccdValidationAndExplicitApprovalControlSubscription() {
        val fixture = Fixture()
        val states = mutableListOf<Boolean>()
        val requests = mutableListOf<ByteArray?>()
        val server = BridgeServer(
            fixture.context,
            peerAuthorizer = BridgeServerPeerAuthorizer.RejectAll,
            onSubscriberStateChanged = states::add,
            onAuthorizationRequested = { _, token -> requests.add(token) },
        )
        assertTrue(server.open())
        val descriptor = fixture.service.characteristics
            .first { it.uuid == BridgeProtocol.TELEMETRY_CHAR_UUID }
            .getDescriptor(BridgeProtocol.CCCD_UUID)

        fixture.callback.onDescriptorWriteRequest(peer, 1, descriptor, true, true, 0, byteArrayOf(1, 0))
        fixture.callback.onDescriptorWriteRequest(peer, 2, descriptor, false, true, 0, byteArrayOf(2, 0))
        verify {
            fixture.gatt.sendResponse(peer, 1, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null)
            fixture.gatt.sendResponse(peer, 2, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, 0, null)
        }
        assertFalse(server.isPending(peer.address))

        fixture.callback.onDescriptorWriteRequest(peer, 3, descriptor, false, true, 0, byteArrayOf(1, 0))
        assertTrue(server.isPending(peer.address.lowercase()))
        assertEquals(1, requests.size)
        assertTrue(server.approvePeer(peer.address.lowercase()))
        assertFalse(server.isPending(peer.address))
        assertEquals(listOf(true), states)

        fixture.callback.onDescriptorWriteRequest(peer, 4, descriptor, false, true, 0, byteArrayOf(0, 0))
        assertEquals(listOf(true, false), states)
        assertFalse(server.approvePeer(peer.address))
        server.stop()
    }

    @Test
    fun pendingPeerTimesOutAndDisconnects() {
        val fixture = Fixture()
        val timedOut = mutableListOf<BluetoothDevice>()
        val server = BridgeServer(
            fixture.context,
            peerAuthorizer = BridgeServerPeerAuthorizer.RejectAll,
            onAuthorizationTimedOut = timedOut::add,
        )
        assertTrue(server.open())
        val descriptor = fixture.service.characteristics
            .first { it.uuid == BridgeProtocol.TELEMETRY_CHAR_UUID }
            .getDescriptor(BridgeProtocol.CCCD_UUID)
        fixture.callback.onDescriptorWriteRequest(peer, 1, descriptor, false, false, 0, byteArrayOf(1, 0))
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)
        assertFalse(server.isPending(peer.address))
        assertEquals(listOf(peer), timedOut)
        verify(exactly = 1) { fixture.gatt.cancelConnection(peer) }
        server.stop()
    }

    @Test
    fun authorizedPeerReceivesLatestFrameOnce() {
        val fixture = Fixture()
        every {
            fixture.gatt.notifyCharacteristicChanged(peer, any(), false, any())
        } returns BluetoothStatusCodes.SUCCESS
        val server = BridgeServer(fixture.context)
        assertTrue(server.open())
        val descriptor = fixture.service.characteristics
            .first { it.uuid == BridgeProtocol.TELEMETRY_CHAR_UUID }
            .getDescriptor(BridgeProtocol.CCCD_UUID)
        fixture.callback.onDescriptorWriteRequest(peer, 1, descriptor, false, false, 0, byteArrayOf(1, 0))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            server.attachSource(scope, flowOf(BridgeFrame.EMPTY.copy(timestampMillis = 1, speedKmh = 20f)))
            verify(exactly = 1) {
                fixture.gatt.notifyCharacteristicChanged(peer, any(), false, any())
            }
            fixture.callback.onNotificationSent(peer, BluetoothGatt.GATT_SUCCESS)
            verify(exactly = 1) {
                fixture.gatt.notifyCharacteristicChanged(peer, any(), false, any())
            }
        } finally {
            scope.cancel()
            server.stop()
        }
    }

    @Test
    fun handshakeAuthorizesPendingSubscriberAndDescriptorReadReflectsState() {
        val fixture = Fixture()
        val token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val states = mutableListOf<Boolean>()
        val server = BridgeServer(
            fixture.context,
            peerAuthorizer = BridgeServerPeerAuthorizer { _, offered ->
                offered?.contentEquals(token) == true
            },
            onSubscriberStateChanged = states::add,
        )
        assertTrue(server.open())
        val descriptor = fixture.service.characteristics
            .first { it.uuid == BridgeProtocol.TELEMETRY_CHAR_UUID }
            .getDescriptor(BridgeProtocol.CCCD_UUID)
        val handshake = fixture.service.characteristics
            .first { it.uuid == BridgeProtocol.HANDSHAKE_CHAR_UUID }

        fixture.callback.onDescriptorWriteRequest(peer, 1, descriptor, false, true, 0, byteArrayOf(1, 0))
        assertTrue(server.isPending(peer.address))
        fixture.callback.onDescriptorReadRequest(peer, 2, 0, descriptor)
        verify { fixture.gatt.sendResponse(peer, 2, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(0, 0)) }

        fixture.callback.onCharacteristicWriteRequest(peer, 3, handshake, false, true, 0, token)
        assertFalse(server.isPending(peer.address))
        assertEquals(listOf(true), states)
        fixture.callback.onDescriptorReadRequest(peer, 4, 1, descriptor)
        verify { fixture.gatt.sendResponse(peer, 4, BluetoothGatt.GATT_SUCCESS, 1, byteArrayOf(0)) }

        fixture.callback.onConnectionStateChange(peer, BluetoothGatt.GATT_SUCCESS, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(listOf(true, false), states)
        server.stop()
    }

    @Test
    fun failedAdvertisementRetriesAndModeSwitchUsesLowLatency() {
        val fixture = Fixture()
        val server = BridgeServer(fixture.context)
        assertTrue(server.open())
        assertEquals(listOf(AdvertiseSettings.ADVERTISE_MODE_BALANCED), fixture.advertiseModes)

        fixture.advertiseCallback.onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(2, fixture.advertiseModes.size)

        assertTrue(server.setAdvertiseMode(true))
        assertEquals(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY, fixture.advertiseModes.last())
        fixture.advertiseCallback.onStartSuccess(null)
        server.stop()
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)
        assertEquals(3, fixture.advertiseModes.size)
    }

    // ---------------------------------------------------------------- open() failures

    @Test
    fun unavailableBluetoothManagerAbortsOpen() {
        val context = mockk<Context>()
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns null
        assertFalse(BridgeServer(context).open())
    }

    @Test
    fun aNullGattServerAbortsOpen() {
        val fixture = Fixture()
        every { fixture.manager.openGattServer(fixture.context, any()) } returns null
        assertFalse(BridgeServer(fixture.context).open())
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun aServiceThePlatformRefusesToRegisterClosesTheGatt() {
        val fixture = Fixture()
        every { fixture.gatt.addService(any()) } returns false
        assertFalse(BridgeServer(fixture.context).open())
        verify(exactly = 1) { fixture.gatt.close() }
    }

    @Test
    fun anInterruptedWaitAbortsOpenEvenWhenClosingTheGattThrows() {
        val fixture = Fixture()
        every { fixture.gatt.close() } throws IllegalStateException("gone")
        val server = BridgeServer(fixture.context)
        Thread.currentThread().interrupt()
        try {
            assertFalse(server.open())
        } finally {
            Thread.interrupted() // do not leak the flag into other tests
        }
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun missingAdvertiserAbortsOpenAndClosesTheGatt() {
        val fixture = Fixture()
        every { fixture.adapter.bluetoothLeAdvertiser } returns null
        assertFalse(BridgeServer(fixture.context).open())
        verify(exactly = 1) { fixture.gatt.close() }
    }

    @Test
    fun pairingTokenIsPublishedInTheScanResponse() {
        val fixture = Fixture()
        val server = BridgeServer(
            fixture.context,
            pairingToken = ByteArray(BridgeProtocol.PAIRING_TOKEN_SIZE) { 7 },
        )
        assertTrue(server.open())
        verify(exactly = 1) {
            fixture.advertiser.startAdvertising(
                any<AdvertiseSettings>(), any<AdvertiseData>(), any<AdvertiseData>(), any<AdvertiseCallback>(),
            )
        }
        server.stop()
    }

    // ---------------------------------------------------------------- characteristic writes

    @Test
    fun malformedHandshakeWritesAreRejectedOnlyWhenAResponseIsWanted() {
        val fixture = Fixture()
        val server = BridgeServer(fixture.context)
        assertTrue(server.open())
        val handshake = fixture.service.characteristics.first { it.uuid == BridgeProtocol.HANDSHAKE_CHAR_UUID }

        fixture.callback.onCharacteristicWriteRequest(peer, 1, handshake, false, true, 0, ByteArray(3))
        fixture.callback.onCharacteristicWriteRequest(
            peer, 2, handshake, false, true, 1, ByteArray(BridgeProtocol.PAIRING_TOKEN_SIZE),
        )
        fixture.callback.onCharacteristicWriteRequest(peer, 3, handshake, false, false, 0, ByteArray(3))

        verify {
            fixture.gatt.sendResponse(peer, 1, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, 0, null)
            fixture.gatt.sendResponse(peer, 2, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, 1, null)
        }
        verify(exactly = 0) { fixture.gatt.sendResponse(peer, 3, any(), any(), any()) }
        server.stop()
    }

    @Test
    fun writesToOtherCharacteristicsAreNotSupported() {
        val fixture = Fixture()
        val server = BridgeServer(fixture.context)
        assertTrue(server.open())
        val telemetry = fixture.service.characteristics.first { it.uuid == BridgeProtocol.TELEMETRY_CHAR_UUID }

        fixture.callback.onCharacteristicWriteRequest(peer, 4, telemetry, false, true, 0, byteArrayOf(1))
        fixture.callback.onCharacteristicWriteRequest(peer, 5, telemetry, false, false, 0, byteArrayOf(1))

        verify { fixture.gatt.sendResponse(peer, 4, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null) }
        verify(exactly = 0) { fixture.gatt.sendResponse(peer, 5, any(), any(), any()) }
        server.stop()
    }

    // ---------------------------------------------------------------- pending approval

    @Test
    fun rejectingAPendingPeerDisconnectsItEvenIfTheStackThrows() {
        val fixture = Fixture()
        every { fixture.gatt.cancelConnection(peer) } throws IllegalStateException("gone")
        val server = BridgeServer(fixture.context, peerAuthorizer = BridgeServerPeerAuthorizer.RejectAll)
        assertTrue(server.open())
        val descriptor = fixture.service.characteristics
            .first { it.uuid == BridgeProtocol.TELEMETRY_CHAR_UUID }
            .getDescriptor(BridgeProtocol.CCCD_UUID)
        fixture.callback.onDescriptorWriteRequest(peer, 1, descriptor, false, false, 0, byteArrayOf(1, 0))
        assertTrue(server.isPending(peer.address))

        assertTrue(server.rejectPeer(peer.address))
        assertFalse(server.isPending(peer.address))
        assertFalse(server.rejectPeer(peer.address)) // already gone
        verify(exactly = 1) { fixture.gatt.cancelConnection(peer) }
        server.stop()
    }

    @Test
    fun aTimeoutIsStillReportedWhenDisconnectingThrows() {
        val fixture = Fixture()
        every { fixture.gatt.cancelConnection(peer) } throws IllegalStateException("gone")
        val timedOut = mutableListOf<BluetoothDevice>()
        val server = BridgeServer(
            fixture.context,
            peerAuthorizer = BridgeServerPeerAuthorizer.RejectAll,
            onAuthorizationTimedOut = timedOut::add,
        )
        assertTrue(server.open())
        val descriptor = fixture.service.characteristics
            .first { it.uuid == BridgeProtocol.TELEMETRY_CHAR_UUID }
            .getDescriptor(BridgeProtocol.CCCD_UUID)
        fixture.callback.onDescriptorWriteRequest(peer, 1, descriptor, false, false, 0, byteArrayOf(1, 0))

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)

        assertEquals(listOf(peer), timedOut)
        server.stop()
    }

    @Test
    fun anUnapprovedHandshakeTokenRaisesAnotherApprovalRequest() {
        val fixture = Fixture()
        val token = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val requests = mutableListOf<ByteArray?>()
        val server = BridgeServer(
            fixture.context,
            peerAuthorizer = BridgeServerPeerAuthorizer.RejectAll,
            onAuthorizationRequested = { _, offered -> requests.add(offered) },
        )
        assertTrue(server.open())
        val descriptor = fixture.service.characteristics
            .first { it.uuid == BridgeProtocol.TELEMETRY_CHAR_UUID }
            .getDescriptor(BridgeProtocol.CCCD_UUID)
        val handshake = fixture.service.characteristics.first { it.uuid == BridgeProtocol.HANDSHAKE_CHAR_UUID }

        fixture.callback.onDescriptorWriteRequest(peer, 1, descriptor, false, false, 0, byteArrayOf(1, 0))
        assertEquals(listOf<ByteArray?>(null), requests)

        fixture.callback.onCharacteristicWriteRequest(peer, 2, handshake, false, false, 0, token)
        assertEquals(2, requests.size)
        assertTrue(requests[1]!!.contentEquals(token))
        assertTrue(server.isPending(peer.address))
        server.stop()
    }

    // ---------------------------------------------------------------- withdrawn approval

    @Test
    fun aSubscriberWhoseApprovalWasWithdrawnIsDisconnectedAndNoLongerFed() {
        val fixture = Fixture()
        every {
            fixture.gatt.notifyCharacteristicChanged(peer, any(), false, any())
        } returns BluetoothStatusCodes.SUCCESS
        val token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val approved = mutableSetOf(BridgePairingToken.toHex(token))
        val states = mutableListOf<Boolean>()
        val server = BridgeServer(
            fixture.context,
            peerAuthorizer = BridgeServerPeerAuthorizer.fromApproved(approvedTokens = { approved.toSet() }),
            onSubscriberStateChanged = states::add,
        )
        assertTrue(server.open())
        val descriptor = fixture.service.characteristics
            .first { it.uuid == BridgeProtocol.TELEMETRY_CHAR_UUID }
            .getDescriptor(BridgeProtocol.CCCD_UUID)
        val handshake = fixture.service.characteristics.first { it.uuid == BridgeProtocol.HANDSHAKE_CHAR_UUID }
        fixture.callback.onCharacteristicWriteRequest(peer, 1, handshake, false, false, 0, token)
        fixture.callback.onDescriptorWriteRequest(peer, 2, descriptor, false, false, 0, byteArrayOf(1, 0))
        assertEquals(listOf(true), states)

        // Still approved: the link is left alone.
        assertEquals(0, server.dropUnapprovedSubscribers())
        verify(exactly = 0) { fixture.gatt.cancelConnection(peer) }
        assertEquals(listOf(true), states)

        approved.clear()
        assertEquals(1, server.dropUnapprovedSubscribers())

        verify(exactly = 1) { fixture.gatt.cancelConnection(peer) }
        assertEquals(listOf(true, false), states)
        assertEquals(0, server.dropUnapprovedSubscribers()) // nothing left to drop
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            server.attachSource(scope, flowOf(BridgeFrame.EMPTY.copy(timestampMillis = 2, speedKmh = 30f)))
            verify(exactly = 0) { fixture.gatt.notifyCharacteristicChanged(peer, any(), false, any()) }
        } finally {
            scope.cancel()
            server.stop()
        }
    }

    @Test
    fun aLegacyCentralApprovedByAddressIsDroppedWhenItsAddressIsWithdrawn() {
        val fixture = Fixture()
        val approvedMacs = mutableSetOf(peer.address)
        val states = mutableListOf<Boolean>()
        val server = BridgeServer(
            fixture.context,
            peerAuthorizer = BridgeServerPeerAuthorizer.fromApproved(
                approvedTokens = { emptySet() },
                approvedMacs = { approvedMacs.toSet() },
            ),
            onSubscriberStateChanged = states::add,
        )
        assertTrue(server.open())
        val descriptor = fixture.service.characteristics
            .first { it.uuid == BridgeProtocol.TELEMETRY_CHAR_UUID }
            .getDescriptor(BridgeProtocol.CCCD_UUID)
        fixture.callback.onDescriptorWriteRequest(peer, 1, descriptor, false, false, 0, byteArrayOf(1, 0))
        assertEquals(listOf(true), states)

        approvedMacs.clear()

        assertEquals(1, server.dropUnapprovedSubscribers())
        verify(exactly = 1) { fixture.gatt.cancelConnection(peer) }
        assertEquals(listOf(true, false), states)
        server.stop()
    }

    @Test
    fun aDisconnectThatThrowsStillStopsTheStream() {
        val fixture = Fixture()
        every { fixture.gatt.cancelConnection(peer) } throws IllegalStateException("gone")
        val approved = mutableSetOf(peer.address)
        val states = mutableListOf<Boolean>()
        val server = BridgeServer(
            fixture.context,
            peerAuthorizer = BridgeServerPeerAuthorizer.fromApproved(
                approvedTokens = { emptySet() },
                approvedMacs = { approved.toSet() },
            ),
            onSubscriberStateChanged = states::add,
        )
        assertTrue(server.open())
        val descriptor = fixture.service.characteristics
            .first { it.uuid == BridgeProtocol.TELEMETRY_CHAR_UUID }
            .getDescriptor(BridgeProtocol.CCCD_UUID)
        fixture.callback.onDescriptorWriteRequest(peer, 1, descriptor, false, false, 0, byteArrayOf(1, 0))
        approved.clear()

        assertEquals(1, server.dropUnapprovedSubscribers())

        assertEquals(listOf(true, false), states)
        server.stop()
    }

    @Test
    fun peersStillWaitingForApprovalAreNotTouchedByTheSweep() {
        val fixture = Fixture()
        val server = BridgeServer(fixture.context, peerAuthorizer = BridgeServerPeerAuthorizer.RejectAll)
        assertTrue(server.open())
        val descriptor = fixture.service.characteristics
            .first { it.uuid == BridgeProtocol.TELEMETRY_CHAR_UUID }
            .getDescriptor(BridgeProtocol.CCCD_UUID)
        fixture.callback.onDescriptorWriteRequest(peer, 1, descriptor, false, false, 0, byteArrayOf(1, 0))
        assertTrue(server.isPending(peer.address))

        assertEquals(0, server.dropUnapprovedSubscribers())

        assertTrue(server.isPending(peer.address)) // the rider can still answer the prompt
        verify(exactly = 0) { fixture.gatt.cancelConnection(peer) }
        server.stop()
    }

    private class Fixture(serviceStatus: Int = BluetoothGatt.GATT_SUCCESS) {
        val context = mockk<Context>()
        val manager = mockk<BluetoothManager>()
        val adapter = mockk<BluetoothAdapter>()
        val advertiser = mockk<BluetoothLeAdvertiser>(relaxed = true)
        val gatt = mockk<BluetoothGattServer>(relaxed = true)
        val events = mutableListOf<String>()
        private val callbackSlot = slot<BluetoothGattServerCallback>()
        private val serviceSlot = slot<BluetoothGattService>()
        private val advertiseCallbackSlot = slot<AdvertiseCallback>()
        val advertiseModes = mutableListOf<Int>()
        val callback: BluetoothGattServerCallback get() = callbackSlot.captured
        val service: BluetoothGattService get() = serviceSlot.captured
        val advertiseCallback: AdvertiseCallback get() = advertiseCallbackSlot.captured

        init {
            every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
            every { manager.openGattServer(context, capture(callbackSlot)) } returns gatt
            every { manager.adapter } returns adapter
            every { adapter.isMultipleAdvertisementSupported } returns true
            every { adapter.bluetoothLeAdvertiser } returns advertiser
            every { gatt.addService(capture(serviceSlot)) } answers {
                events.add("addService")
                callback.onServiceAdded(serviceStatus, service)
                true
            }
            every {
                advertiser.startAdvertising(
                    any<AdvertiseSettings>(), any<AdvertiseData>(), capture(advertiseCallbackSlot),
                )
            } answers {
                events.add("advertise")
                advertiseModes.add(firstArg<AdvertiseSettings>().mode)
            }
        }
    }
}
