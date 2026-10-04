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
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.safety.MotionInterlock
import com.rideflux.domain.transport.BleTransport
import com.rideflux.protocol.familyscooter.connection.ScooterConnectionImpl
import com.rideflux.protocol.familyscooter.ninebot.ScooterHandshakeStateMachine
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScooterRepositoryEdgeTest {
    private class Env(adapterPresent: Boolean = true, scannerPresent: Boolean = true) {
        val context = mockk<Context>()
        val adapter = mockk<BluetoothAdapter>()
        val scanner = mockk<BluetoothLeScanner>(relaxed = true)
        val callbackSlot = slot<ScanCallback>()
        val callback: ScanCallback get() = callbackSlot.captured

        init {
            val manager = mockk<BluetoothManager>()
            every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
            every { manager.adapter } returns if (adapterPresent) adapter else null
            every { adapter.bluetoothLeScanner } returns if (scannerPresent) scanner else null
            every { scanner.startScan(null, any<ScanSettings>(), capture(callbackSlot)) } returns Unit
        }

        fun result(address: String?, name: String?, deviceName: String? = null, record: Boolean = true): ScanResult {
            val device = mockk<BluetoothDevice>()
            val result = mockk<ScanResult>()
            every { result.device } returns device
            every { device.address } returns address
            every { device.name } returns deviceName
            if (record) {
                val scanRecord = mockk<ScanRecord>()
                every { result.scanRecord } returns scanRecord
                every { scanRecord.deviceName } returns name
                every { scanRecord.serviceUuids } returns null
            } else {
                every { result.scanRecord } returns null
            }
            return result
        }
    }

    private class ThrowingTransport : BleTransport {
        override val incoming: Flow<ByteArray> = MutableSharedFlow()
        override suspend fun connect() { throw IOException("no link") }
        override suspend fun disconnect() = Unit
        override suspend fun write(bytes: ByteArray) = Unit
    }

    @Test fun `scan fails when there is no adapter or no scanner`() = runTest {
        for (env in listOf(Env(adapterPresent = false), Env(scannerPresent = false))) {
            val repository = ScooterRepositoryImpl(env.context, backgroundScope)
            try {
                repository.scan().collect { }
                fail("expected IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message!!.contains("unavailable"))
            }
        }
    }

    @Test fun `scan surfaces start and callback failures`() = runTest {
        val broken = Env()
        every { broken.scanner.startScan(null, any<ScanSettings>(), any<ScanCallback>()) } throws
            IllegalStateException("denied")
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { ScooterRepositoryImpl(broken.context, backgroundScope).scan().collect { } }
        }
        val env = Env()
        var failure: Throwable? = null
        val job = backgroundScope.launch {
            try { ScooterRepositoryImpl(env.context, backgroundScope).scan().collect { } }
            catch (t: Throwable) { failure = t }
        }
        runCurrent()
        env.callback.onScanFailed(3)
        runCurrent()
        assertTrue(failure is IOException)
        assertTrue(failure!!.message!!.contains("errorCode=3"))
        job.cancel()
    }

    @Test fun `scan handles batches, device names and incomplete results`() = runTest {
        val env = Env()
        val repository = ScooterRepositoryImpl(env.context, backgroundScope)
        val emissions = mutableListOf<List<ScooterDevice>>()
        backgroundScope.launch { repository.scan().collect(emissions::add) }
        runCurrent()
        env.callback.onScanResult(0, env.result(null, "ES2-1"))
        env.callback.onScanResult(0, env.result("AA", null, record = false))
        env.callback.onScanResult(0, env.result("BB", "  ", deviceName = "Heart rate"))
        runCurrent()
        assertTrue(emissions.isEmpty())
        assertFalse(repository.isDiscovered("AA"))
        env.callback.onBatchScanResults(mutableListOf(
            env.result("CC", null, deviceName = "ES2-9"), env.result("DD", "G30-2")))
        runCurrent()
        assertEquals(listOf("ES2-9", "G30-2"), emissions.last().map { it.model })
        assertTrue(repository.isDiscovered("CC"))
        assertTrue(repository.isDiscovered("DD"))
    }

    @Test fun `connect refuses models without a verified profile and rolls back`() = runTest {
        val env = Env()
        val repository = ScooterRepositoryImpl(env.context, backgroundScope)
        assertThrows(UnsupportedOperationException::class.java) {
            kotlinx.coroutines.runBlocking { repository.connect("unknown") }
        }
        assertTrue(repository.activeConnections().first().isEmpty())
        backgroundScope.launch { repository.scan().collect { } }
        runCurrent()
        env.callback.onScanResult(0, env.result("MI", "MIScooter3456"))
        runCurrent()
        assertThrows(UnsupportedOperationException::class.java) {
            kotlinx.coroutines.runBlocking { repository.connect("MI") }
        }
        env.callback.onScanResult(0, env.result("ES", "ES2-1"))
        runCurrent()
        every { env.adapter.getRemoteDevice("ES") } throws IllegalArgumentException("bad address")
        val failure = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { repository.connect("ES") }
        }
        assertTrue(failure.message!!.contains("Invalid scooter address"))
        assertTrue(repository.activeConnections().first().isEmpty())
    }

    @Test fun `a failing factory and a failing start both leave the repository usable`() = runTest {
        var calls = 0
        val repository = ScooterRepositoryImpl(backgroundScope) { address, model, scope ->
            calls++
            if (calls == 1) throw IllegalStateException("factory")
            val gate = MotionInterlock()
            ScooterConnectionImpl(ThrowingTransport(), ScooterDevice(address, model), scope,
                ScooterHandshakeStateMachine(gate) { testScheduler.currentTime }, gate,
                clock = { testScheduler.currentTime })
        }
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { repository.connect("x") }
        }
        assertTrue(repository.activeConnections().first().isEmpty())
        val connection = repository.connect("x")
        runCurrent()
        assertNotNull(connection)
        assertTrue(connection.state.value is com.rideflux.domain.connection.ConnectionState.Failed)
        connection.close()
        assertTrue(repository.activeConnections().first().isEmpty())
    }
}
