/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.source

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import com.rideflux.data.bridge.BleScanThrottle
import com.rideflux.data.bridge.BridgeProtocol
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
@Config(sdk = [36])
class BridgePeerScannerTest {
    @Test
    fun filtersByServiceAndDeduplicatesRotatingAddressesByPairingToken() = runTest {
        val fixture = Fixture()
        val scanner = BridgePeerScanner(fixture.context, BleScanThrottle(now = { 0L }))
        val results = mutableListOf<List<BridgePeerCandidate>>()
        val job = backgroundScope.launch { scanner.candidates().collect { results.add(it) } }
        runCurrent()

        fixture.callback.onScanResult(0, fixture.result("AA:BB:CC:DD:EE:FF", -60, service = false))
        runCurrent()
        assertTrue(results.isEmpty())

        val token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        fixture.callback.onScanResult(0, fixture.result("AA:BB:CC:DD:EE:FF", -60, token = token))
        fixture.callback.onScanResult(0, fixture.result("11:22:33:44:55:66", -40, token = token))
        runCurrent()
        assertEquals(1, results.last().size)
        assertEquals("11:22:33:44:55:66", results.last().single().address)
        assertEquals("0102", results.last().single().shortCode)

        fixture.callback.onScanResult(0, fixture.result("77:88:99:AA:BB:CC", -30))
        runCurrent()
        assertEquals(listOf(-30, -40), results.last().map { it.rssi })
        job.cancelAndJoin()
        verify(exactly = 1) { fixture.scanner.stopScan(any<ScanCallback>()) }
    }

    @Test
    fun scanFailurePropagatesToCollector() = runTest {
        val fixture = Fixture()
        val scanner = BridgePeerScanner(fixture.context, BleScanThrottle(now = { 0L }))
        var failure: Throwable? = null
        val job = backgroundScope.launch {
            try {
                scanner.candidates().collect()
            } catch (t: Throwable) {
                failure = t
            }
        }
        runCurrent()
        fixture.callback.onScanFailed(3)
        runCurrent()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("Bridge scan failed: 3") == true)
        job.cancelAndJoin()
    }

    private class Fixture {
        val context = mockk<Context>()
        private val manager = mockk<BluetoothManager>()
        private val adapter = mockk<BluetoothAdapter>()
        val scanner = mockk<BluetoothLeScanner>(relaxed = true)
        private val callbackSlot = slot<ScanCallback>()
        val callback: ScanCallback get() = callbackSlot.captured

        init {
            every { context.applicationContext } returns context
            every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
            every { manager.adapter } returns adapter
            every { adapter.bluetoothLeScanner } returns scanner
            every { scanner.startScan(any(), any(), capture(callbackSlot)) } returns Unit
        }

        fun result(address: String, rssi: Int, service: Boolean = true, token: ByteArray? = null): ScanResult {
            val device = mockk<BluetoothDevice>()
            val record = mockk<ScanRecord>()
            val result = mockk<ScanResult>()
            every { result.device } returns device
            every { result.scanRecord } returns record
            every { result.rssi } returns rssi
            every { device.address } returns address
            every { record.deviceName } returns "RideFlux"
            every { record.serviceUuids } returns if (service) {
                listOf(ParcelUuid(BridgeProtocol.SERVICE_UUID))
            } else emptyList()
            every { record.getServiceData(ParcelUuid(BridgeProtocol.SERVICE_UUID)) } returns token
            return result
        }
    }
}
