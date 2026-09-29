/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BridgeClientGattTest {
    @Test
    fun connectsSubscribesDeliversFramesAndClosesGattOnCancellation() = runTest {
        val fixture = Fixture()
        val client = BridgeClient(
            fixture.context,
            scanThrottle = BleScanThrottle(now = { 0L }),
        )
        val frames = mutableListOf<BridgeFrame>()
        val job = backgroundScope.launch { client.frames().collect { frames.add(it) } }
        runCurrent()
        fixture.scanCallback.onScanResult(0, fixture.scanResult)
        fixture.gattCallback.onConnectionStateChange(
            fixture.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
        )
        verify(exactly = 1) { fixture.gatt.discoverServices() } // MTU request was rejected.
        fixture.gattCallback.onServicesDiscovered(fixture.gatt, BluetoothGatt.GATT_SUCCESS)
        verify(exactly = 1) {
            fixture.gatt.writeDescriptor(fixture.cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
        fixture.gattCallback.onDescriptorWrite(fixture.gatt, fixture.cccd, BluetoothGatt.GATT_SUCCESS)

        val sent = BridgeFrame.EMPTY.copy(timestampMillis = 1, speedKmh = 20f)
        fixture.gattCallback.onCharacteristicChanged(fixture.gatt, fixture.telemetry, BridgeCodec.encode(sent))
        runCurrent()
        assertEquals(listOf(sent), frames)

        job.cancelAndJoin()
        verify(atLeast = 1) { fixture.scanner.stopScan(any<ScanCallback>()) }
        verify(exactly = 1) { fixture.gatt.disconnect() }
        verify(exactly = 1) { fixture.gatt.close() }
    }

    @Test
    fun rejectsUnapprovedScanResultWithoutOpeningGatt() = runTest {
        val fixture = Fixture()
        val client = BridgeClient(
            fixture.context,
            peerFilter = BridgePeerFilter.RejectAll,
            scanThrottle = BleScanThrottle(now = { 0L }),
        )
        val job = backgroundScope.launch { client.frames().collect() }
        runCurrent()
        fixture.scanCallback.onScanResult(0, fixture.scanResult)
        verify(exactly = 0) {
            fixture.device.connectGatt(fixture.context, false, any(), BluetoothDevice.TRANSPORT_LE)
        }
        job.cancelAndJoin()
    }

    @Test
    fun scanFailureClosesFlowWithAnError() = runTest {
        val fixture = Fixture()
        val client = BridgeClient(fixture.context, scanThrottle = BleScanThrottle(now = { 0L }))
        var error: Throwable? = null
        val job = backgroundScope.launch {
            try {
                client.frames().collect()
            } catch (t: Throwable) {
                error = t
            }
        }
        runCurrent()
        fixture.scanCallback.onScanFailed(2)
        runCurrent()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message?.contains("scan failed: 2") == true)
        job.cancelAndJoin()
    }

    private class Fixture {
        val context = mockk<Context>()
        val manager = mockk<BluetoothManager>()
        val adapter = mockk<BluetoothAdapter>()
        val scanner = mockk<BluetoothLeScanner>(relaxed = true)
        val device = mockk<BluetoothDevice>()
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val scanRecord = mockk<ScanRecord>()
        val scanResult = mockk<ScanResult>()
        val telemetry = BluetoothGattCharacteristic(
            BridgeProtocol.TELEMETRY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        )
        val cccd = BluetoothGattDescriptor(BridgeProtocol.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE)
        private val service = BluetoothGattService(
            BridgeProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        private val scanSlot = slot<ScanCallback>()
        private val gattSlot = slot<BluetoothGattCallback>()
        val scanCallback: ScanCallback get() = scanSlot.captured
        val gattCallback: BluetoothGattCallback get() = gattSlot.captured

        init {
            telemetry.addDescriptor(cccd)
            service.addCharacteristic(telemetry)
            every { context.applicationContext } returns context
            every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
            every { manager.adapter } returns adapter
            every { adapter.bluetoothLeScanner } returns scanner
            every { scanner.startScan(any(), any(), capture(scanSlot)) } returns Unit
            every { device.address } returns "AA:BB:CC:DD:EE:FF"
            every {
                device.connectGatt(context, false, capture(gattSlot), BluetoothDevice.TRANSPORT_LE)
            } returns gatt
            every { scanRecord.serviceUuids } returns listOf(ParcelUuid(BridgeProtocol.SERVICE_UUID))
            every { scanRecord.getServiceData(any()) } returns null
            every { scanResult.device } returns device
            every { scanResult.scanRecord } returns scanRecord
            every { scanResult.rssi } returns -50
            every { gatt.requestMtu(any()) } returns false
            every { gatt.discoverServices() } returns true
            every { gatt.getService(BridgeProtocol.SERVICE_UUID) } returns service
            every { gatt.setCharacteristicNotification(telemetry, true) } returns true
            every {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } returns BluetoothGatt.GATT_SUCCESS
        }
    }
}
