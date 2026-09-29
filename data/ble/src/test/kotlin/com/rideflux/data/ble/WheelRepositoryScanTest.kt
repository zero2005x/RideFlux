/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.rideflux.domain.wheel.WheelFamily
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
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WheelRepositoryScanTest {
    @Test
    fun mergesAdvertisementAndScanResponseWithoutEmittingForRssiOnlyChanges() = runTest {
        val fixture = Fixture()
        val repository = WheelRepositoryImpl(fixture.context, backgroundScope)
        val emissions = mutableListOf<List<com.rideflux.domain.repository.DiscoveredWheel>>()
        val job = backgroundScope.launch { repository.scan().collect { emissions.add(it) } }
        runCurrent()

        fixture.callback.onScanResult(0, fixture.result("AA:BB:CC:DD:EE:FF", "V5F-2A4AC0", -55))
        runCurrent()
        assertEquals(WheelFamily.I1, emissions.single().single().family)

        // The scan response supplies the service while omitting the name.
        fixture.callback.onScanResult(
            0,
            fixture.result(
                "AA:BB:CC:DD:EE:FF", null, -80,
                listOf("0000ffe0-0000-1000-8000-00805f9b34fb"),
            ),
        )
        runCurrent()
        assertEquals(1, emissions.size)
        assertEquals("V5F-2A4AC0", emissions.single().single().displayName)

        // Unknown named devices stay selectable as unclassified candidates.
        fixture.callback.onScanResult(0, fixture.result("11:22:33:44:55:66", "New Wheel", -65))
        runCurrent()
        assertEquals(2, emissions.last().size)
        assertEquals(null, emissions.last()[1].family)
        assertEquals(-80, emissions.last()[0].rssi)

        job.cancelAndJoin()
        verify(exactly = 1) { fixture.scanner.stopScan(any<ScanCallback>()) }
    }

    @Test
    fun scanFailureClosesCollectionWithErrorAndStopsScanner() = runTest {
        val fixture = Fixture()
        val repository = WheelRepositoryImpl(fixture.context, backgroundScope)
        var failure: Throwable? = null
        val job = backgroundScope.launch {
            try {
                repository.scan().collect()
            } catch (t: Throwable) {
                failure = t
            }
        }
        runCurrent()
        fixture.callback.onScanFailed(6)
        runCurrent()
        assertTrue(failure is IOException)
        assertTrue(failure?.message?.contains("errorCode=6") == true)
        job.cancelAndJoin()
        verify(exactly = 1) { fixture.scanner.stopScan(any<ScanCallback>()) }
    }

    private class Fixture {
        val context = mockk<Context>()
        private val manager = mockk<BluetoothManager>()
        private val adapter = mockk<BluetoothAdapter>()
        val scanner = mockk<BluetoothLeScanner>(relaxed = true)
        private val callbackSlot = slot<ScanCallback>()
        val callback: ScanCallback get() = callbackSlot.captured

        init {
            every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
            every { manager.adapter } returns adapter
            every { adapter.bluetoothLeScanner } returns scanner
            every { scanner.startScan(null, any<ScanSettings>(), capture(callbackSlot)) } returns Unit
        }

        fun result(address: String, name: String?, rssi: Int, services: List<String> = emptyList()): ScanResult {
            val device = mockk<BluetoothDevice>()
            val record = mockk<ScanRecord>()
            val result = mockk<ScanResult>()
            every { result.device } returns device
            every { result.scanRecord } returns record
            every { result.rssi } returns rssi
            every { device.address } returns address
            every { device.name } returns null
            every { record.deviceName } returns name
            every { record.serviceUuids } returns services.map { ParcelUuid(UUID.fromString(it)) }
            return result
        }
    }
}
