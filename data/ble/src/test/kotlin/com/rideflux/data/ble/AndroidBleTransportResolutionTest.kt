/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import com.rideflux.domain.wheel.WheelFamily
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The production discovery callback must feed the resolver and bind the
 * characteristics of the resolved topology — not the pre-connect guess.
 *
 * This drives `AndroidBleTransport.onServicesDiscovered` directly (the platform
 * GATT objects are stand-ins) so the assertion covers the callback → resolver →
 * characteristic binding sequence that the fake-transport end-to-end test
 * cannot reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidBleTransportResolutionTest {

    private val context = mockk<Context>(relaxed = true)
    private val device = mockk<BluetoothDevice>(relaxed = true)
    private val factory = WheelCodecFactoryImpl()
    private val resolver = WheelSessionResolver(factory)

    private fun setField(transport: AndroidBleTransport, name: String, value: Any?) {
        AndroidBleTransport::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .set(transport, value)
    }

    private fun getField(transport: AndroidBleTransport, name: String): Any? =
        AndroidBleTransport::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(transport)

    private fun callback(transport: AndroidBleTransport): BluetoothGattCallback =
        getField(transport, "callback") as BluetoothGattCallback

    private fun singleCharGatt(): BluetoothGatt {
        val characteristic = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { characteristic.uuid } returns GattUuids.CHAR_FFE1
        val service = mockk<BluetoothGattService>(relaxed = true)
        every { service.uuid } returns GattUuids.SERVICE_FFE0
        every { service.characteristics } returns listOf(characteristic)
        every { service.getCharacteristic(GattUuids.CHAR_FFE1) } returns characteristic
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        every { gatt.services } returns listOf(service)
        return gatt
    }

    private fun nusGatt(): BluetoothGatt {
        val descriptor = mockk<BluetoothGattDescriptor>(relaxed = true)
        val tx = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { tx.uuid } returns GattUuids.CHAR_NUS_TX
        every { tx.getDescriptor(GattUuids.DESCRIPTOR_CCC) } returns descriptor
        val rx = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { rx.uuid } returns GattUuids.CHAR_NUS_RX
        val service = mockk<BluetoothGattService>(relaxed = true)
        every { service.uuid } returns GattUuids.SERVICE_NUS
        every { service.characteristics } returns listOf(rx, tx)
        every { service.getCharacteristic(GattUuids.CHAR_NUS_TX) } returns tx
        every { service.getCharacteristic(GattUuids.CHAR_NUS_RX) } returns rx
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        every { gatt.services } returns listOf(service)
        every { gatt.getService(any()) } returns null
        every { gatt.getService(GattUuids.SERVICE_NUS) } returns service
        every { gatt.setCharacteristicNotification(tx, true) } returns true
        every { gatt.writeDescriptor(descriptor, any()) } returns BluetoothGatt.GATT_SUCCESS
        return gatt
    }

    @Test
    fun `ambiguous shared UUID fails discovery before any characteristic is bound`() {
        every { device.name } returns null
        val transport = AndroidBleTransport(context, device, GattTopology.SINGLE_CHAR) { snapshot ->
            resolver.resolve(SessionHints(), snapshot)
        }
        val gatt = singleCharGatt()
        setField(transport, "gatt", gatt)

        callback(transport).onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        val resolution = transport.resolution
        assertNotNull(resolution)
        assertEquals(ResolutionConfidence.AMBIGUOUS, resolution!!.confidence)
        assertEquals(
            listOf(WheelFamily.G, WheelFamily.GX, WheelFamily.K, WheelFamily.V, WheelFamily.N1),
            resolution.candidates,
        )
        // The ambiguous result must close the link instead of binding a guessed topology.
        verify(exactly = 1) { gatt.close() }
        assertEquals(null, getField(transport, "notifyChar"))
    }

    @Test
    fun `resolved NUS session binds the NUS characteristics from the resolution`() {
        every { device.name } returns "VESC"
        val transport = AndroidBleTransport(context, device, GattTopology.SINGLE_CHAR) { snapshot ->
            resolver.resolve(SessionHints(manualFamily = WheelFamily.VESC), snapshot)
        }
        val gatt = nusGatt()
        setField(transport, "gatt", gatt)

        callback(transport).onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        val resolution = transport.resolution
        assertNotNull(resolution)
        assertEquals(WheelFamily.VESC, resolution!!.family)
        assertEquals(GattTopology.NORDIC_UART, resolution.topology)
        assertEquals(GattUuids.CHAR_NUS_TX, (getField(transport, "notifyChar") as BluetoothGattCharacteristic).uuid)
        assertEquals(GattUuids.CHAR_NUS_RX, (getField(transport, "writeChar") as BluetoothGattCharacteristic).uuid)
        // The discovered name is part of the snapshot the resolver saw.
        assertEquals("VESC", transport.gattSnapshot?.deviceName)
        assertTrue(transport.gattSnapshot?.services?.containsKey(GattUuids.SERVICE_NUS) == true)
    }
}
