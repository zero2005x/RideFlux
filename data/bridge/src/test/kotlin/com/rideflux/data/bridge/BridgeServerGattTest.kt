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
@Config(sdk = [33])
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

    private class Fixture(serviceStatus: Int = BluetoothGatt.GATT_SUCCESS) {
        val context = mockk<Context>()
        val manager = mockk<BluetoothManager>()
        val adapter = mockk<BluetoothAdapter>()
        val advertiser = mockk<BluetoothLeAdvertiser>(relaxed = true)
        val gatt = mockk<BluetoothGattServer>(relaxed = true)
        val events = mutableListOf<String>()
        private val callbackSlot = slot<BluetoothGattServerCallback>()
        private val serviceSlot = slot<BluetoothGattService>()
        val callback: BluetoothGattServerCallback get() = callbackSlot.captured
        val service: BluetoothGattService get() = serviceSlot.captured

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
                    any<AdvertiseSettings>(), any<AdvertiseData>(), any<AdvertiseCallback>(),
                )
            } answers { events.add("advertise") }
        }
    }
}
