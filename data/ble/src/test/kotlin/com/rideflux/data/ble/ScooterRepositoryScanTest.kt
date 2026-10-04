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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScooterRepositoryScanTest {
    @Test fun mergesNameAndUuidAcrossPacketsWithoutRssiChurn() = runTest {
        val fixture = Fixture()
        val repository = ScooterRepositoryImpl(fixture.context, backgroundScope)
        val emissions = mutableListOf<List<com.rideflux.domain.device.ScooterDevice>>()
        val job = backgroundScope.launch { repository.scan().collect(emissions::add) }
        runCurrent()
        fixture.callback.onScanResult(0, fixture.result("AA:BB:CC:DD:EE:FF", "MIScooter3456"))
        runCurrent()
        assertEquals("MIScooter3456", emissions.single().single().model)
        fixture.callback.onScanResult(0, fixture.result("AA:BB:CC:DD:EE:FF", null,
            listOf(GattUuids.SERVICE_FE95.toString())))
        runCurrent()
        assertEquals(1, emissions.size)
        fixture.callback.onScanResult(0, fixture.result("11:22:33:44:55:66", "Inmotion V11",
            listOf(GattUuids.SERVICE_FE95.toString())))
        runCurrent()
        assertEquals(1, emissions.size)
        fixture.callback.onScanResult(0, fixture.result("22:33:44:55:66:77", null,
            listOf(GattUuids.SERVICE_FE95.toString())))
        runCurrent()
        assertEquals(2, emissions.last().size)
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

        fun result(address: String, name: String?, services: List<String> = emptyList()): ScanResult {
            val device = mockk<BluetoothDevice>()
            val record = mockk<ScanRecord>()
            val result = mockk<ScanResult>()
            every { result.device } returns device
            every { result.scanRecord } returns record
            every { device.address } returns address
            every { device.name } returns null
            every { record.deviceName } returns name
            every { record.serviceUuids } returns services.map { ParcelUuid(UUID.fromString(it)) }
            return result
        }
    }
}
