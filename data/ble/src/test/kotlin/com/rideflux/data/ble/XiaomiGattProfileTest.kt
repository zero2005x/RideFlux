/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class XiaomiGattProfileTest {

    private fun mockService(
        fe95Present: Boolean = true,
        nusPresent: Boolean = true,
        upnpNotify: Boolean = true,
        avdtpNotify: Boolean = true,
        nusTxNotify: Boolean = true,
        nusRxWrite: Boolean = true,
    ): BluetoothGatt {
        val gatt = mockk<BluetoothGatt>()
        if (fe95Present) {
            val fe95 = mockk<BluetoothGattService>()
            val upnp = BluetoothGattCharacteristic(
                GattUuids.CHAR_MI_UPNP,
                (if (upnpNotify) BluetoothGattCharacteristic.PROPERTY_NOTIFY else 0) or
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                0,
            )
            val avdtp = BluetoothGattCharacteristic(
                GattUuids.CHAR_MI_AVDTP,
                (if (avdtpNotify) BluetoothGattCharacteristic.PROPERTY_NOTIFY else 0) or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                0,
            )
            every { fe95.getCharacteristic(GattUuids.CHAR_MI_UPNP) } returns upnp
            every { fe95.getCharacteristic(GattUuids.CHAR_MI_AVDTP) } returns avdtp
            every { gatt.getService(GattUuids.SERVICE_FE95) } returns fe95
        } else {
            every { gatt.getService(GattUuids.SERVICE_FE95) } returns null
        }

        if (nusPresent) {
            val nus = mockk<BluetoothGattService>()
            val tx = BluetoothGattCharacteristic(
                GattUuids.CHAR_NUS_TX,
                if (nusTxNotify) BluetoothGattCharacteristic.PROPERTY_NOTIFY else 0,
                0,
            )
            val rx = BluetoothGattCharacteristic(
                GattUuids.CHAR_NUS_RX,
                if (nusRxWrite) BluetoothGattCharacteristic.PROPERTY_WRITE else 0,
                0,
            )
            every { nus.getCharacteristic(GattUuids.CHAR_NUS_TX) } returns tx
            every { nus.getCharacteristic(GattUuids.CHAR_NUS_RX) } returns rx
            every { gatt.getService(GattUuids.SERVICE_NUS) } returns nus
        } else {
            every { gatt.getService(GattUuids.SERVICE_NUS) } returns null
        }

        return gatt
    }

    @Test
    fun resolveSucceedsWhenAllRequiredCharacteristicsArePresent() {
        val gatt = mockService()
        val profile = XiaomiGattProfile.resolve(gatt)

        assertNotNull(profile)
        assertEquals(3, profile.notifications.size)
        assertEquals(GattUuids.CHAR_MI_UPNP, profile.upnp.uuid)
        assertEquals(GattUuids.CHAR_MI_AVDTP, profile.avdtp.uuid)
        assertEquals(GattUuids.CHAR_NUS_RX, profile.uartWrite.uuid)
        assertEquals(GattUuids.CHAR_NUS_TX, profile.uartNotify.uuid)
    }

    @Test
    fun resolveThrowsWhenFe95ServiceAbsent() {
        val gatt = mockService(fe95Present = false)
        assertThrows(IOException::class.java) {
            XiaomiGattProfile.resolve(gatt)
        }
    }

    @Test
    fun resolveThrowsWhenNusServiceAbsent() {
        val gatt = mockService(nusPresent = false)
        assertThrows(IOException::class.java) {
            XiaomiGattProfile.resolve(gatt)
        }
    }

    @Test
    fun resolveThrowsWhenNotificationPropertyMissing() {
        val gatt = mockService(upnpNotify = false)
        assertThrows(IOException::class.java) {
            XiaomiGattProfile.resolve(gatt)
        }
    }

    @Test
    fun resolveThrowsWhenAvdtpNotificationPropertyMissing() {
        val gatt = mockService(avdtpNotify = false)
        assertThrows(IOException::class.java) { XiaomiGattProfile.resolve(gatt) }
    }

    @Test
    fun resolveThrowsWhenNusTxNotificationPropertyMissing() {
        val gatt = mockService(nusTxNotify = false)
        assertThrows(IOException::class.java) { XiaomiGattProfile.resolve(gatt) }
    }

    @Test
    fun resolveThrowsWhenNusRxWritePropertyMissing() {
        val gatt = mockService(nusRxWrite = false)
        assertThrows(IOException::class.java) { XiaomiGattProfile.resolve(gatt) }
    }

    @Test
    fun writeTypeHandlesWriteAndWriteNoResponse() {
        val writeChar = BluetoothGattCharacteristic(
            GattUuids.CHAR_NUS_RX, BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, XiaomiGattProfile.writeType(writeChar))

        val writeNoRespChar = BluetoothGattCharacteristic(
            GattUuids.CHAR_NUS_RX, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, 0)
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, XiaomiGattProfile.writeType(writeNoRespChar))

        val readOnly = BluetoothGattCharacteristic(
            GattUuids.CHAR_NUS_RX, BluetoothGattCharacteristic.PROPERTY_READ, 0)
        assertThrows(IOException::class.java) {
            XiaomiGattProfile.writeType(readOnly)
        }
    }
}
