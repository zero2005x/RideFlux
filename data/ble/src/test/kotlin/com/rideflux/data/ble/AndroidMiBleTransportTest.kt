/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.rideflux.domain.transport.MiAuthChar
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidMiBleTransportTest {
    private val context = mockk<Context>(relaxed = true)
    private val device = mockk<BluetoothDevice>(relaxed = true)
    private lateinit var transport: AndroidMiBleTransport

    @Before
    fun setUp() {
        every { device.address } returns "11:22:33:44:55:66"
        transport = AndroidMiBleTransport(context, device, connectTimeoutMillis = 1_000, writeTimeoutMillis = 1_000)
    }

    private fun gattCallback(): BluetoothGattCallback =
        AndroidMiBleTransport::class.java.getDeclaredField("callback")
            .apply { isAccessible = true }
            .get(transport) as BluetoothGattCallback

    @Test
    fun connectFailsWhenConnectGattReturnsNull() {
        every { device.connectGatt(context, false, any(), BluetoothDevice.TRANSPORT_LE) } returns null
        val err = assertThrows(IOException::class.java) {
            runBlocking { transport.connect() }
        }
        assertTrue(err.message.orEmpty().contains("Xiaomi connection failed"))
    }

    @Test
    fun connectPropagatesSecurityException() {
        every {
            device.connectGatt(context, false, any(), BluetoothDevice.TRANSPORT_LE)
        } throws SecurityException("bluetooth revoked")

        assertThrows(IOException::class.java) {
            runBlocking { transport.connect() }
        }
    }

    @Test
    fun connectTimesOutWhenServiceDiscoveryNeverCompletes() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        every { device.connectGatt(context, false, any(), BluetoothDevice.TRANSPORT_LE) } returns gatt

        assertThrows(IOException::class.java) {
            runBlocking { transport.connect() }
        }
        verify { gatt.close() }
    }

    @Test
    fun notificationDeliveryRoutesUpnpAvdtpAndUart() = runBlocking {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val upnp = BluetoothGattCharacteristic(GattUuids.CHAR_MI_UPNP, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
        val avdtp = BluetoothGattCharacteristic(GattUuids.CHAR_MI_AVDTP, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
        val uartWrite = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_RX, BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
        val uartNotify = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        val profile = XiaomiGattProfile(upnp, avdtp, uartWrite, uartNotify)

        AndroidMiBleTransport::class.java.getDeclaredField("profile").apply { isAccessible = true }.set(transport, profile)
        AndroidMiBleTransport::class.java.getDeclaredField("gatt").apply { isAccessible = true }.set(transport, gatt)
        AndroidMiBleTransport::class.java.getDeclaredField("ready").apply { isAccessible = true }.set(transport, true)

        val callback = gattCallback()

        // Deliver UPNP packet
        val upnpBytes = byteArrayOf(0x23, 0x00, 0x00, 0x00)
        val authJob1 = launch {
            val notification = transport.authNotifications.first()
            assertEquals(MiAuthChar.UPNP, notification.characteristic)
            val bytes = notification.copyBytes()
            try {
                assertArrayEquals(upnpBytes, bytes)
            } finally {
                bytes.fill(0)
                notification.wipe()
            }
        }
        callback.onCharacteristicChanged(gatt, upnp, upnpBytes)
        authJob1.join()

        // Deliver UART packet
        val uartBytes = byteArrayOf(0x55, 0xAB.toByte(), 0x02, 0x01)
        val dataJob = launch {
            val chunk = transport.incoming.first()
            assertArrayEquals(uartBytes, chunk)
        }
        callback.onCharacteristicChanged(gatt, uartNotify, uartBytes)
        dataJob.join()

        transport.disconnect()
    }

    @Test
    fun successfulConnectWritesAndDisconnect() {
        runBlocking {
            val gatt = mockk<BluetoothGatt>(relaxed = true)
            every { device.connectGatt(context, false, any(), BluetoothDevice.TRANSPORT_LE) } returns gatt

            val upnp = BluetoothGattCharacteristic(GattUuids.CHAR_MI_UPNP, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
            val avdtp = BluetoothGattCharacteristic(GattUuids.CHAR_MI_AVDTP, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
            val uartWrite = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_RX, BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
            val uartNotify = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)

            val upnpCcc = BluetoothGattDescriptor(GattUuids.DESCRIPTOR_CCC, 0)
            val avdtpCcc = BluetoothGattDescriptor(GattUuids.DESCRIPTOR_CCC, 0)
            val nusCcc = BluetoothGattDescriptor(GattUuids.DESCRIPTOR_CCC, 0)

            upnp.addDescriptor(upnpCcc)
            avdtp.addDescriptor(avdtpCcc)
            uartNotify.addDescriptor(nusCcc)

            val fe95 = mockk<BluetoothGattService>()
            every { fe95.getCharacteristic(GattUuids.CHAR_MI_UPNP) } returns upnp
            every { fe95.getCharacteristic(GattUuids.CHAR_MI_AVDTP) } returns avdtp
            val nus = mockk<BluetoothGattService>()
            every { nus.getCharacteristic(GattUuids.CHAR_NUS_RX) } returns uartWrite
            every { nus.getCharacteristic(GattUuids.CHAR_NUS_TX) } returns uartNotify

            every { gatt.getService(GattUuids.SERVICE_FE95) } returns fe95
            every { gatt.getService(GattUuids.SERVICE_NUS) } returns nus
            every { gatt.discoverServices() } returns true
            every { gatt.setCharacteristicNotification(any(), true) } returns true
            every { gatt.writeDescriptor(any(), any()) } returns android.bluetooth.BluetoothStatusCodes.SUCCESS
            every { gatt.writeCharacteristic(any(), any(), any()) } returns android.bluetooth.BluetoothStatusCodes.SUCCESS

            val callback = gattCallback()
            val connectJob = launch(start = CoroutineStart.UNDISPATCHED) { transport.connect() }

            callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
            callback.onDescriptorWrite(gatt, upnpCcc, BluetoothGatt.GATT_SUCCESS)
            callback.onDescriptorWrite(gatt, avdtpCcc, BluetoothGatt.GATT_SUCCESS)
            callback.onDescriptorWrite(gatt, nusCcc, BluetoothGatt.GATT_SUCCESS)

            connectJob.join()

            // Calling connect again when already ready returns immediately
            transport.connect()

            // Test write to UART
            val writeJob1 = launch(start = CoroutineStart.UNDISPATCHED) { transport.write(byteArrayOf(0x55, 0xAB.toByte(), 0x01, 0x02)) }
            callback.onCharacteristicWrite(gatt, uartWrite, BluetoothGatt.GATT_SUCCESS)
            writeJob1.join()

            // Test writeAuth UPNP
            val writeJob2 = launch(start = CoroutineStart.UNDISPATCHED) { transport.writeAuth(MiAuthChar.UPNP, byteArrayOf(0x24, 0, 0, 0)) }
            callback.onCharacteristicWrite(gatt, upnp, BluetoothGatt.GATT_SUCCESS)
            writeJob2.join()

            // Test writeAuth AVDTP
            val writeJob3 = launch(start = CoroutineStart.UNDISPATCHED) { transport.writeAuth(MiAuthChar.AVDTP, byteArrayOf(0, 0, 1, 1)) }
            callback.onCharacteristicWrite(gatt, avdtp, BluetoothGatt.GATT_SUCCESS)
            writeJob3.join()

            // Test legacy onCharacteristicChanged
            val uartData = byteArrayOf(0x55, 0xAB.toByte(), 0x02, 0x03)
            @Suppress("DEPRECATION")
            uartNotify.value = uartData
            val legacyReceiveJob = launch(start = CoroutineStart.UNDISPATCHED) {
                val received = transport.incoming.first()
                assertArrayEquals(uartData, received)
            }
            callback.onCharacteristicChanged(gatt, uartNotify)
            legacyReceiveJob.join()

            // Disconnect
            transport.disconnect()
            verify { gatt.close() }

            assertThrows(IllegalStateException::class.java) {
                runBlocking { transport.connect() }
            }
            assertThrows(IOException::class.java) {
                runBlocking { transport.write(byteArrayOf(1)) }
            }
        }
    }

    @Test
    fun errorCallbacksTerminateConnection() {
        runBlocking {
            val gatt = mockk<BluetoothGatt>(relaxed = true)
            every { device.connectGatt(context, false, any(), BluetoothDevice.TRANSPORT_LE) } returns gatt
            val callback = gattCallback()

            val job1 = launch(start = CoroutineStart.UNDISPATCHED) {
                assertThrows(IOException::class.java) { runBlocking { transport.connect() } }
            }
            callback.onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
            job1.join()

            val t2 = AndroidMiBleTransport(context, device, 1_000, 1_000)
            val cb2 = AndroidMiBleTransport::class.java.getDeclaredField("callback").apply { isAccessible = true }.get(t2) as BluetoothGattCallback
            val job2 = launch(start = CoroutineStart.UNDISPATCHED) {
                assertThrows(IOException::class.java) { runBlocking { t2.connect() } }
            }
            cb2.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb2.onServicesDiscovered(gatt, BluetoothGatt.GATT_FAILURE)
            job2.join()

            val t3 = AndroidMiBleTransport(context, device, 1_000, 1_000)
            val cb3 = AndroidMiBleTransport::class.java.getDeclaredField("callback").apply { isAccessible = true }.get(t3) as BluetoothGattCallback
            val upnp = BluetoothGattCharacteristic(GattUuids.CHAR_MI_UPNP, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
            val avdtp = BluetoothGattCharacteristic(GattUuids.CHAR_MI_AVDTP, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
            val uartWrite = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_RX, BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
            val uartNotify = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
            val upnpCcc = BluetoothGattDescriptor(GattUuids.DESCRIPTOR_CCC, 0)
            upnp.addDescriptor(upnpCcc)
            val fe95 = mockk<BluetoothGattService>()
            every { fe95.getCharacteristic(GattUuids.CHAR_MI_UPNP) } returns upnp
            every { fe95.getCharacteristic(GattUuids.CHAR_MI_AVDTP) } returns avdtp
            val nus = mockk<BluetoothGattService>()
            every { nus.getCharacteristic(GattUuids.CHAR_NUS_RX) } returns uartWrite
            every { nus.getCharacteristic(GattUuids.CHAR_NUS_TX) } returns uartNotify
            every { gatt.getService(GattUuids.SERVICE_FE95) } returns fe95
            every { gatt.getService(GattUuids.SERVICE_NUS) } returns nus
            every { gatt.setCharacteristicNotification(any(), true) } returns true
            every { gatt.writeDescriptor(any(), any()) } returns android.bluetooth.BluetoothStatusCodes.SUCCESS

            val job3 = launch(start = CoroutineStart.UNDISPATCHED) {
                assertThrows(IOException::class.java) { runBlocking { t3.connect() } }
            }
            cb3.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb3.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
            cb3.onDescriptorWrite(gatt, upnpCcc, BluetoothGatt.GATT_FAILURE)
            job3.join()
        }
    }

    @Test
    fun writeFailuresPropagate() {
        runBlocking {
            val gatt = mockk<BluetoothGatt>(relaxed = true)
            val upnp = BluetoothGattCharacteristic(GattUuids.CHAR_MI_UPNP, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
            val avdtp = BluetoothGattCharacteristic(GattUuids.CHAR_MI_AVDTP, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
            val uartWrite = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_RX, BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
            val uartNotify = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
            val profile = XiaomiGattProfile(upnp, avdtp, uartWrite, uartNotify)

            AndroidMiBleTransport::class.java.getDeclaredField("profile").apply { isAccessible = true }.set(transport, profile)
            AndroidMiBleTransport::class.java.getDeclaredField("gatt").apply { isAccessible = true }.set(transport, gatt)
            AndroidMiBleTransport::class.java.getDeclaredField("ready").apply { isAccessible = true }.set(transport, true)

            every { gatt.writeCharacteristic(any(), any(), any()) } returns android.bluetooth.BluetoothStatusCodes.ERROR_UNKNOWN
            assertThrows(IOException::class.java) {
                runBlocking { transport.write(byteArrayOf(1, 2)) }
            }

            every { gatt.writeCharacteristic(any(), any(), any()) } returns android.bluetooth.BluetoothStatusCodes.SUCCESS
            val callback = gattCallback()
            val writeJob = launch(start = CoroutineStart.UNDISPATCHED) {
                assertThrows(IOException::class.java) { runBlocking { transport.write(byteArrayOf(1, 2)) } }
            }
            callback.onCharacteristicWrite(gatt, uartWrite, BluetoothGatt.GATT_FAILURE)
            writeJob.join()
        }
    }

    @Test
    fun timeoutSettingsRequirePositiveValues() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidMiBleTransport(context, device, connectTimeoutMillis = 0, writeTimeoutMillis = 1000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidMiBleTransport(context, device, connectTimeoutMillis = 1000, writeTimeoutMillis = 0)
        }
    }

    @Test
    fun writeRejectsWhenNotConnectedOrNotReady() {
        // gatt is null
        val err1 = assertThrows(IOException::class.java) {
            runBlocking { transport.write(byteArrayOf(1, 2)) }
        }
        assertTrue(err1.message.orEmpty().contains("Xiaomi transport is not connected"))

        // gatt present but not ready
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        AndroidMiBleTransport::class.java.getDeclaredField("gatt").apply { isAccessible = true }.set(transport, gatt)
        val err2 = assertThrows(IOException::class.java) {
            runBlocking { transport.write(byteArrayOf(1, 2)) }
        }
        assertTrue(err2.message.orEmpty().contains("Xiaomi transport is not ready"))

        // transport closed
        AndroidMiBleTransport::class.java.getDeclaredField("closed").apply { isAccessible = true }.set(transport, true)
        val err3 = assertThrows(IOException::class.java) {
            runBlocking { transport.write(byteArrayOf(1, 2)) }
        }
        assertTrue(err3.message.orEmpty().contains("Xiaomi transport is not ready"))
    }

    @Test
    fun writeTimesOutWhenCharacteristicWriteNeverCompletes() {
        runBlocking {
            val gatt = mockk<BluetoothGatt>(relaxed = true)
            val upnp = BluetoothGattCharacteristic(GattUuids.CHAR_MI_UPNP, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
            val avdtp = BluetoothGattCharacteristic(GattUuids.CHAR_MI_AVDTP, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
            val uartWrite = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_RX, BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
            val uartNotify = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
            val profile = XiaomiGattProfile(upnp, avdtp, uartWrite, uartNotify)

            val shortTimeoutTransport = AndroidMiBleTransport(context, device, connectTimeoutMillis = 1000, writeTimeoutMillis = 50)
            AndroidMiBleTransport::class.java.getDeclaredField("profile").apply { isAccessible = true }.set(shortTimeoutTransport, profile)
            AndroidMiBleTransport::class.java.getDeclaredField("gatt").apply { isAccessible = true }.set(shortTimeoutTransport, gatt)
            AndroidMiBleTransport::class.java.getDeclaredField("ready").apply { isAccessible = true }.set(shortTimeoutTransport, true)

            every { gatt.writeCharacteristic(any(), any(), any()) } returns android.bluetooth.BluetoothStatusCodes.SUCCESS
            val err = assertThrows(IOException::class.java) {
                runBlocking { shortTimeoutTransport.write(byteArrayOf(1, 2)) }
            }
            assertTrue(err.message.orEmpty().contains("Xiaomi write timed out"))
        }
    }

    @Test
    fun callbackIgnoresForeignGattOrClosed() {
        val foreignGatt = mockk<BluetoothGatt>(relaxed = true)
        val callback = gattCallback()

        // None of these should throw or change state for foreign gatt
        callback.onConnectionStateChange(foreignGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback.onServicesDiscovered(foreignGatt, BluetoothGatt.GATT_SUCCESS)
        callback.onDescriptorWrite(foreignGatt, mockk(relaxed = true), BluetoothGatt.GATT_SUCCESS)
        callback.onCharacteristicWrite(foreignGatt, mockk(relaxed = true), BluetoothGatt.GATT_SUCCESS)
        callback.onCharacteristicChanged(foreignGatt, mockk(relaxed = true), byteArrayOf(1))
    }

    @Test
    fun connectionStateChangeBranchesHandled() {
        runBlocking {
            val gatt = mockk<BluetoothGatt>(relaxed = true)
            every { device.connectGatt(context, false, any(), BluetoothDevice.TRANSPORT_LE) } returns gatt
            val callback = gattCallback()

            // 1. STATE_CONNECTED but discoverServices returns false
            val tReject = AndroidMiBleTransport(context, device, 1000, 1000)
            val cbReject = AndroidMiBleTransport::class.java.getDeclaredField("callback").apply { isAccessible = true }.get(tReject) as BluetoothGattCallback
            every { gatt.discoverServices() } returns false
            var rejectFailed = false
            val rejectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    tReject.connect()
                } catch (e: IOException) {
                    rejectFailed = e.message.orEmpty().contains("Xiaomi connection failed")
                }
            }
            cbReject.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            rejectJob.join()
            assertTrue(rejectFailed)

            // 2. STATE_CONNECTED but discoverServices throws
            val tThrow = AndroidMiBleTransport(context, device, 1000, 1000)
            val cbThrow = AndroidMiBleTransport::class.java.getDeclaredField("callback").apply { isAccessible = true }.get(tThrow) as BluetoothGattCallback
            every { gatt.discoverServices() } throws RuntimeException("discovery crash")
            var throwFailed = false
            val throwJob = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    tThrow.connect()
                } catch (e: IOException) {
                    throwFailed = e.message.orEmpty().contains("Xiaomi connection failed")
                }
            }
            cbThrow.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            throwJob.join()
            assertTrue(throwFailed)

            // 3. STATE_DISCONNECTED with GATT_SUCCESS terminates
            val tDisc = AndroidMiBleTransport(context, device, 1000, 1000)
            val cbDisc = AndroidMiBleTransport::class.java.getDeclaredField("callback").apply { isAccessible = true }.get(tDisc) as BluetoothGattCallback
            var discFailed = false
            val discJob = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    tDisc.connect()
                } catch (_: IOException) {
                    discFailed = true
                }
            }
            cbDisc.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
            discJob.join()
            assertTrue(discFailed)
        }
    }

    @Test
    fun servicesDiscoveredFailsWhenGattProfileMissing() {
        runBlocking {
            val gatt = mockk<BluetoothGatt>(relaxed = true)
            every { device.connectGatt(context, false, any(), BluetoothDevice.TRANSPORT_LE) } returns gatt
            // No FE95 / NUS services
            every { gatt.getService(any()) } returns null
            every { gatt.discoverServices() } returns true

            val callback = gattCallback()
            val connectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                assertThrows(IOException::class.java) { runBlocking { transport.connect() } }
            }
            callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
            connectJob.join()
        }
    }

    @Test
    fun descriptorWriteIgnoresUnmatchedDescriptor() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val expected = BluetoothGattDescriptor(GattUuids.DESCRIPTOR_CCC, 0)
        val unexpected = BluetoothGattDescriptor(GattUuids.DESCRIPTOR_CCC, 0)

        AndroidMiBleTransport::class.java.getDeclaredField("gatt").apply { isAccessible = true }.set(transport, gatt)
        AndroidMiBleTransport::class.java.getDeclaredField("expectedDescriptor").apply { isAccessible = true }.set(transport, expected)

        val callback = gattCallback()
        // Unmatched descriptor must be safely ignored without advancing cccIndex or throwing
        callback.onDescriptorWrite(gatt, unexpected, BluetoothGatt.GATT_SUCCESS)
        val currentExpected = AndroidMiBleTransport::class.java.getDeclaredField("expectedDescriptor").apply { isAccessible = true }.get(transport)
        assertEquals(expected, currentExpected)
    }

    @Test
    fun characteristicWriteIgnoresUnmatchedOrNoPending() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        AndroidMiBleTransport::class.java.getDeclaredField("gatt").apply { isAccessible = true }.set(transport, gatt)

        val callback = gattCallback()
        val c1 = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_RX, 0, 0)
        // With no pending write, must not throw
        callback.onCharacteristicWrite(gatt, c1, BluetoothGatt.GATT_SUCCESS)
    }

    @Test
    fun notificationRoutingAndBufferOverflow() = runBlocking {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val upnp = BluetoothGattCharacteristic(GattUuids.CHAR_MI_UPNP, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
        val avdtp = BluetoothGattCharacteristic(GattUuids.CHAR_MI_AVDTP, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
        val uartWrite = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_RX, BluetoothGattCharacteristic.PROPERTY_WRITE, 0)
        val uartNotify = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        val profile = XiaomiGattProfile(upnp, avdtp, uartWrite, uartNotify)

        AndroidMiBleTransport::class.java.getDeclaredField("profile").apply { isAccessible = true }.set(transport, profile)
        AndroidMiBleTransport::class.java.getDeclaredField("gatt").apply { isAccessible = true }.set(transport, gatt)
        AndroidMiBleTransport::class.java.getDeclaredField("ready").apply { isAccessible = true }.set(transport, true)

        val callback = gattCallback()

        // 1. Route AVDTP packet
        val avdtpBytes = byteArrayOf(0x00, 0x00, 0x01, 0x01)
        val authJob = launch {
            val notification = transport.authNotifications.first()
            assertEquals(MiAuthChar.AVDTP, notification.characteristic)
            assertArrayEquals(avdtpBytes, notification.copyBytes())
        }
        callback.onCharacteristicChanged(gatt, avdtp, avdtpBytes)
        authJob.join()

        // 2. Unknown characteristic ignored
        val unknown = BluetoothGattCharacteristic(java.util.UUID.randomUUID(), 0, 0)
        callback.onCharacteristicChanged(gatt, unknown, byteArrayOf(99))

        // 3. Overflow data packets channel (capacity 256)
        val overflowData = byteArrayOf(0x55, 0xAA.toByte())
        for (i in 0 until 256) {
            callback.onCharacteristicChanged(gatt, uartNotify, overflowData)
        }
        // 257th packet overflows channel and terminates transport
        callback.onCharacteristicChanged(gatt, uartNotify, overflowData)

        // Transport is now terminated / closed
        val closedField = AndroidMiBleTransport::class.java.getDeclaredField("closed").apply { isAccessible = true }.get(transport) as Boolean
        assertTrue(closedField)
    }

    @Test
    fun legacyCharacteristicChangedIgnoresNullValue() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val uartNotify = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        val upnp = BluetoothGattCharacteristic(GattUuids.CHAR_MI_UPNP, 0, 0)
        val avdtp = BluetoothGattCharacteristic(GattUuids.CHAR_MI_AVDTP, 0, 0)
        val uartWrite = BluetoothGattCharacteristic(GattUuids.CHAR_NUS_RX, 0, 0)
        val profile = XiaomiGattProfile(upnp, avdtp, uartWrite, uartNotify)

        AndroidMiBleTransport::class.java.getDeclaredField("profile").apply { isAccessible = true }.set(transport, profile)
        AndroidMiBleTransport::class.java.getDeclaredField("gatt").apply { isAccessible = true }.set(transport, gatt)
        AndroidMiBleTransport::class.java.getDeclaredField("ready").apply { isAccessible = true }.set(transport, true)

        @Suppress("DEPRECATION")
        uartNotify.value = null
        val callback = gattCallback()
        @Suppress("DEPRECATION")
        callback.onCharacteristicChanged(gatt, uartNotify)
    }

    @Test
    fun toStringRedactsSecrets() {
        assertEquals("AndroidMiBleTransport(<redacted>)", transport.toString())
    }
}
