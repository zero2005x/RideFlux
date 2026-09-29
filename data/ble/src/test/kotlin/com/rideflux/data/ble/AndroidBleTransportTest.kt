/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

/**
 * Link-up failures, teardown and notification delivery of the real GATT transport. The platform
 * objects are stand-ins; the transport's private GATT state is set directly where a full
 * connect/discovery handshake would only add timing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidBleTransportTest {
    private val context = mockk<Context>(relaxed = true)
    private val device = mockk<BluetoothDevice>(relaxed = true)
    private lateinit var transport: AndroidBleTransport

    @Before
    fun setUp() {
        every { device.address } returns "AA:BB:CC:DD:EE:FF"
        transport = AndroidBleTransport(context, device, GattTopology.SINGLE_CHAR)
    }

    private fun setField(name: String, value: Any?) {
        AndroidBleTransport::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .set(transport, value)
    }

    private fun gattCallback(): BluetoothGattCallback =
        AndroidBleTransport::class.java.getDeclaredField("callback")
            .apply { isAccessible = true }
            .get(transport) as BluetoothGattCallback

    private fun notifyCharacteristic() =
        BluetoothGattCharacteristic(GattUuids.CHAR_FFE1, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)

    // ---------------------------------------------------------------- connect

    @Test
    fun connectReportsAConnectGattThatReturnsNull() {
        every { device.connectGatt(context, false, any(), BluetoothDevice.TRANSPORT_LE) } returns null

        val error = assertThrows(IOException::class.java) { runBlocking { transport.connect() } }

        assertTrue(error.message, error.message.orEmpty().contains("connectGatt returned null"))
    }

    @Test
    fun connectPropagatesAPermissionFailureFromConnectGatt() {
        every {
            device.connectGatt(context, false, any(), BluetoothDevice.TRANSPORT_LE)
        } throws SecurityException("revoked")

        val error = assertThrows(SecurityException::class.java) { runBlocking { transport.connect() } }

        assertEquals("revoked", error.message)
    }

    @Test
    fun aConnectThatNeverCompletesClosesItsGattWhenCancelled() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        every { device.connectGatt(context, false, any(), BluetoothDevice.TRANSPORT_LE) } returns gatt

        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking { withTimeout(200) { transport.connect() } }
        }

        verify(exactly = 1) { gatt.close() }
        // The cancelled attempt also failed the receive side, so a reader is not left waiting.
        assertThrows(IOException::class.java) { runBlocking { transport.incoming.toList() } }
    }

    @Test
    fun aClosedTransportRefusesToConnect() {
        runBlocking { transport.disconnect() }
        runBlocking { transport.disconnect() } // second call is a no-op

        val error = assertThrows(IllegalStateException::class.java) { runBlocking { transport.connect() } }

        assertTrue(error.message.orEmpty().contains("transport has been closed"))
    }

    // ---------------------------------------------------------------- disconnect

    @Test
    fun disconnectClosesAGattThatNeverFinishedConnecting() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        setField("gatt", gatt)

        runBlocking { transport.disconnect() }

        verify(exactly = 1) { gatt.close() }
        verify(exactly = 0) { gatt.disconnect() }
    }

    @Test
    fun disconnectTearsDownAnEstablishedLinkEvenIfCloseThrows() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        every { gatt.close() } throws IllegalStateException("gone")
        setField("gatt", gatt)
        setField("connected", true)

        runBlocking { transport.disconnect() }

        verify(exactly = 1) { gatt.disconnect() }
        verify(exactly = 1) { gatt.close() }
    }

    @Test
    fun writesAreRefusedWithoutALink() {
        val error = assertThrows(IOException::class.java) { runBlocking { transport.write(byteArrayOf(1)) } }
        assertEquals("not connected", error.message)
    }

    // ---------------------------------------------------------------- notifications

    @Suppress("DEPRECATION")
    @Test
    fun notificationsFromTheLiveLinkAreDeliveredInOrder() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val notify = notifyCharacteristic()
        setField("gatt", gatt)
        setField("notifyChar", notify)
        val callback = gattCallback()

        callback.onCharacteristicChanged(gatt, notify, byteArrayOf(1, 2))
        // The pre-API-33 overload reads the value off the characteristic.
        notify.value = byteArrayOf(3)
        callback.onCharacteristicChanged(gatt, notify)

        val received = runBlocking { withTimeout(2_000) { transport.incoming.take(2).toList() } }
        assertArrayEquals(byteArrayOf(1, 2), received[0])
        assertArrayEquals(byteArrayOf(3), received[1])
    }

    @Suppress("DEPRECATION")
    @Test
    fun notificationsFromOtherLinksOrCharacteristicsAreIgnored() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val notify = notifyCharacteristic()
        val other = BluetoothGattCharacteristic(UUID.randomUUID(), BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        setField("gatt", gatt)
        setField("notifyChar", notify)
        val callback = gattCallback()

        callback.onCharacteristicChanged(gatt, other, byteArrayOf(9))
        callback.onCharacteristicChanged(mockk(relaxed = true), notify, byteArrayOf(8))
        other.value = byteArrayOf(7)
        callback.onCharacteristicChanged(gatt, other)
        // A characteristic with no value yet has nothing to deliver.
        callback.onCharacteristicChanged(gatt, notify)
        callback.onCharacteristicChanged(gatt, notify, byteArrayOf(5))

        val received = runBlocking { withTimeout(2_000) { transport.incoming.take(1).toList() } }
        assertArrayEquals(byteArrayOf(5), received.single())
    }

    @Test
    fun aClosedTransportDropsLateNotificationsAndEndsTheStream() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val notify = notifyCharacteristic()
        setField("gatt", gatt)
        setField("notifyChar", notify)
        val callback = gattCallback()

        runBlocking { transport.disconnect() }
        callback.onCharacteristicChanged(gatt, notify, byteArrayOf(1))

        assertTrue(runBlocking { transport.incoming.toList() }.isEmpty())
    }
}
