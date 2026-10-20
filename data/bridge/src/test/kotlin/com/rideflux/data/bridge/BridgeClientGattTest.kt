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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import android.os.Looper
import android.os.Build
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import com.rideflux.domain.settings.HudLayoutProfile

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BridgeClientGattTest {
    @Test
    fun missingHudProfileResponseTimesOutAndIsPolledAgain() = runTest {
        val fixture = Fixture()
        val profileCharacteristic = fixture.addHudProfile()
        every { fixture.gatt.readCharacteristic(profileCharacteristic) } returns true
        val client = BridgeClient(fixture.context, scanThrottle = BleScanThrottle(now = { 0L }))
        val job = backgroundScope.launch { client.frames().collect() }
        runCurrent()
        connectAndDiscover(fixture)
        fixture.gattCallback.onDescriptorWrite(fixture.gatt, fixture.cccd, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        verify(exactly = 1) { fixture.gatt.readCharacteristic(profileCharacteristic) }

        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
        advanceTimeBy(1_000)
        runCurrent()
        verify(exactly = 2) { fixture.gatt.readCharacteristic(profileCharacteristic) }
        job.cancelAndJoin()
    }

    @Test
    @Config(sdk = [28])
    @Suppress("DEPRECATION")
    fun legacyGattReadCallbackDecodesHudProfile() = runTest {
        val fixture = Fixture()
        val profileCharacteristic = fixture.addHudProfile()
        every { fixture.gatt.writeDescriptor(fixture.cccd) } returns true
        every { fixture.gatt.readCharacteristic(profileCharacteristic) } returns true
        val received = mutableListOf<HudLayoutProfile>()
        val client = BridgeClient(
            fixture.context,
            scanThrottle = BleScanThrottle(now = { 0L }),
            onHudProfile = received::add,
        )
        val job = backgroundScope.launch { client.frames().collect() }
        runCurrent()
        connectAndDiscover(fixture)
        fixture.gattCallback.onDescriptorWrite(fixture.gatt, fixture.cccd, BluetoothGatt.GATT_SUCCESS)
        runCurrent()

        val profile = HudLayoutProfile(topInset = 10)
        profileCharacteristic.value = HudProfileCodec.encode(profile)
        fixture.gattCallback.onCharacteristicRead(fixture.gatt, profileCharacteristic, BluetoothGatt.GATT_SUCCESS)
        assertEquals(listOf(profile), received)
        job.cancelAndJoin()
    }

    @Test
    fun rejectedHudProfileReadIsRetriedWithoutWaitingForAResponse() = runTest {
        val fixture = Fixture()
        val profileCharacteristic = fixture.addHudProfile()
        every { fixture.gatt.readCharacteristic(profileCharacteristic) } returns false
        val received = mutableListOf<HudLayoutProfile>()
        val client = BridgeClient(
            fixture.context,
            scanThrottle = BleScanThrottle(now = { 0L }),
            onHudProfile = received::add,
        )
        val job = backgroundScope.launch { client.frames().collect() }
        runCurrent()
        connectAndDiscover(fixture)
        fixture.gattCallback.onDescriptorWrite(fixture.gatt, fixture.cccd, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        verify(exactly = 2) { fixture.gatt.readCharacteristic(profileCharacteristic) }
        assertTrue(received.isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun subscribedClientPollsAndDecodesHudProfileWithoutForwardingInvalidData() = runTest {
        val fixture = Fixture()
        val profileCharacteristic = fixture.addHudProfile()
        every { fixture.gatt.readCharacteristic(profileCharacteristic) } returns true
        val received = mutableListOf<HudLayoutProfile>()
        val client = BridgeClient(
            fixture.context,
            scanThrottle = BleScanThrottle(now = { 0L }),
            onHudProfile = received::add,
        )
        val job = backgroundScope.launch { client.frames().collect() }
        runCurrent()
        connectAndDiscover(fixture)
        fixture.gattCallback.onDescriptorWrite(fixture.gatt, fixture.cccd, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        verify(exactly = 1) { fixture.gatt.readCharacteristic(profileCharacteristic) }
        advanceTimeBy(1_000)
        runCurrent()
        verify(exactly = 1) { fixture.gatt.readCharacteristic(profileCharacteristic) }

        val profile = HudLayoutProfile(leftInset = 8, fontPercent = 120)
        fixture.gattCallback.onCharacteristicRead(fixture.gatt, fixture.telemetry, HudProfileCodec.encode(profile), BluetoothGatt.GATT_SUCCESS)
        assertTrue(received.isEmpty())
        fixture.gattCallback.onCharacteristicRead(
            fixture.gatt, profileCharacteristic, HudProfileCodec.encode(profile), BluetoothGatt.GATT_SUCCESS,
        )
        assertEquals(listOf(profile), received)

        advanceTimeBy(1_000)
        runCurrent()
        verify(exactly = 2) { fixture.gatt.readCharacteristic(profileCharacteristic) }
        fixture.gattCallback.onCharacteristicRead(
            fixture.gatt, profileCharacteristic, byteArrayOf(1), BluetoothGatt.GATT_SUCCESS,
        )
        fixture.gattCallback.onCharacteristicRead(
            fixture.gatt, profileCharacteristic, HudProfileCodec.encode(profile), BluetoothGatt.GATT_FAILURE,
        )
        assertEquals(listOf(profile), received)
        job.cancelAndJoin()
    }

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

        val sent = BridgeFrame.EMPTY.copy(timestampMillis = 1_000L, speedKmh = 20f)
        fixture.gattCallback.onCharacteristicChanged(fixture.gatt, fixture.telemetry, byteArrayOf(1, 2))
        runCurrent()
        assertTrue(frames.isEmpty())
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

    @Test
    fun writesHandshakeBeforeSubscribingAndSurfacesCccdFailure() = runTest {
        val fixture = Fixture()
        val token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val client = BridgeClient(
            fixture.context,
            scanThrottle = BleScanThrottle(now = { 0L }),
            clientToken = token,
        )
        var error: Throwable? = null
        val job = backgroundScope.launch {
            try {
                client.frames().collect()
            } catch (t: Throwable) {
                error = t
            }
        }
        runCurrent()
        fixture.scanCallback.onScanResult(0, fixture.scanResult)
        fixture.gattCallback.onConnectionStateChange(
            fixture.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
        )
        fixture.gattCallback.onServicesDiscovered(fixture.gatt, BluetoothGatt.GATT_SUCCESS)
        verify(exactly = 1) {
            fixture.gatt.writeCharacteristic(
                fixture.handshake, token, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
        }
        verify(exactly = 0) {
            fixture.gatt.writeDescriptor(fixture.cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        fixture.gattCallback.onCharacteristicWrite(
            fixture.gatt, fixture.handshake, BluetoothGatt.GATT_SUCCESS,
        )
        verify(exactly = 1) {
            fixture.gatt.writeDescriptor(fixture.cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
        fixture.gattCallback.onDescriptorWrite(fixture.gatt, fixture.cccd, BluetoothGatt.GATT_FAILURE)
        runCurrent()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message?.contains("CCCD write failed") == true)
        job.cancelAndJoin()
        verify(exactly = 1) { fixture.gatt.close() }
    }

    // ---------------------------------------------------------------- failure paths

    private class Collected(val job: Job) {
        var error: Throwable? = null
        fun message(): String = error?.message.orEmpty()
    }

    private fun TestScope.collectErrors(client: BridgeClient): Collected {
        lateinit var holder: Collected
        val job = backgroundScope.launch {
            try {
                client.frames().collect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                holder.error = t
            }
        }
        holder = Collected(job)
        return holder
    }

    private fun newClient(
        fixture: Fixture,
        token: ByteArray? = null,
        throttle: BleScanThrottle = BleScanThrottle(now = { 0L }),
    ) = BridgeClient(fixture.context, scanThrottle = throttle, clientToken = token)

    /** Scan hit, link up, services discovered: the point where the client subscribes. */
    private fun connectAndDiscover(fixture: Fixture) {
        fixture.scanCallback.onScanResult(0, fixture.scanResult)
        fixture.gattCallback.onConnectionStateChange(
            fixture.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
        )
        fixture.gattCallback.onServicesDiscovered(fixture.gatt, BluetoothGatt.GATT_SUCCESS)
    }

    @Test
    fun status133DisconnectsAreRetriedTwiceThenSurfaced() = runTest {
        val fixture = Fixture()
        every { fixture.gatt.device } returns fixture.device
        val run = collectErrors(newClient(fixture))
        runCurrent()
        fixture.scanCallback.onScanResult(0, fixture.scanResult)

        repeat(2) {
            fixture.gattCallback.onConnectionStateChange(fixture.gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
            advanceTimeBy(700)
            runCurrent()
        }
        verify(exactly = 3) {
            fixture.device.connectGatt(fixture.context, false, any(), BluetoothDevice.TRANSPORT_LE)
        }
        verify(exactly = 2) { fixture.gatt.disconnect() }
        assertNull(run.error)

        fixture.gattCallback.onConnectionStateChange(fixture.gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
        runCurrent()
        assertTrue(run.message(), run.message().contains("disconnected status=133"))
    }

    @Test
    fun plainDisconnectEndsTheFlow() = runTest {
        val fixture = Fixture()
        val run = collectErrors(newClient(fixture))
        runCurrent()
        fixture.scanCallback.onScanResult(0, fixture.scanResult)
        fixture.gattCallback.onConnectionStateChange(fixture.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        runCurrent()
        assertTrue(run.message(), run.message().contains("disconnected status=8"))
    }

    @Test
    fun rejectedNotificationRegistrationIsReported() = runTest {
        val fixture = Fixture()
        every { fixture.gatt.setCharacteristicNotification(fixture.telemetry, true) } returns false
        val run = collectErrors(newClient(fixture))
        runCurrent()
        connectAndDiscover(fixture)
        runCurrent()
        assertTrue(run.message(), run.message().contains("setCharacteristicNotification failed"))
    }

    @Test
    fun missingCccdIsReported() = runTest {
        val fixture = Fixture()
        val bare = BluetoothGattCharacteristic(BridgeProtocol.TELEMETRY_CHAR_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        val bareService = BluetoothGattService(BridgeProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            .apply { addCharacteristic(bare) }
        every { fixture.gatt.getService(BridgeProtocol.SERVICE_UUID) } returns bareService
        every { fixture.gatt.setCharacteristicNotification(bare, true) } returns true
        val run = collectErrors(newClient(fixture))
        runCurrent()
        connectAndDiscover(fixture)
        runCurrent()
        assertTrue(run.message(), run.message().contains("CCCD missing"))
    }

    @Test
    fun rejectedCccdWriteIsReported() = runTest {
        val fixture = Fixture()
        every {
            fixture.gatt.writeDescriptor(fixture.cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } returns BluetoothGatt.GATT_FAILURE
        val run = collectErrors(newClient(fixture))
        runCurrent()
        connectAndDiscover(fixture)
        runCurrent()
        assertTrue(run.message(), run.message().contains("writeDescriptor returned"))
    }

    @Test
    fun revokedPermissionDuringSubscribeIsReported() = runTest {
        val fixture = Fixture()
        every {
            fixture.gatt.writeDescriptor(fixture.cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } throws SecurityException("denied")
        val run = collectErrors(newClient(fixture))
        runCurrent()
        connectAndDiscover(fixture)
        runCurrent()
        assertTrue(run.message(), run.message().contains("CCCD subscribe failed: denied"))
    }

    @Test
    fun throwingHandshakeWriteFallsBackToSubscribingDirectly() = runTest {
        val fixture = Fixture()
        every {
            fixture.gatt.writeCharacteristic(fixture.handshake, any(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } throws IllegalStateException("busy")
        val run = collectErrors(newClient(fixture, token = ByteArray(8) { it.toByte() }))
        runCurrent()
        connectAndDiscover(fixture)
        verify(exactly = 1) {
            fixture.gatt.writeDescriptor(fixture.cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
        assertNull(run.error)
    }

    @Test
    fun failedHandshakeConfirmationStillSubscribes() = runTest {
        val fixture = Fixture()
        val run = collectErrors(newClient(fixture, token = ByteArray(8) { it.toByte() }))
        runCurrent()
        connectAndDiscover(fixture)
        fixture.gattCallback.onCharacteristicWrite(fixture.gatt, fixture.handshake, BluetoothGatt.GATT_FAILURE)
        verify(exactly = 1) {
            fixture.gatt.writeDescriptor(fixture.cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
        assertNull(run.error)
    }

    @Test
    fun telemetryCharacteristicVanishingAfterHandshakeIsReported() = runTest {
        val fixture = Fixture()
        val run = collectErrors(newClient(fixture, token = ByteArray(8) { it.toByte() }))
        runCurrent()
        connectAndDiscover(fixture)
        every { fixture.gatt.getService(BridgeProtocol.SERVICE_UUID) } returns
            BluetoothGattService(BridgeProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        fixture.gattCallback.onCharacteristicWrite(fixture.gatt, fixture.handshake, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertTrue(run.message(), run.message().contains("telemetry characteristic missing after handshake"))
    }

    @Test
    fun connectGattThrowingIsReported() = runTest {
        val fixture = Fixture()
        every {
            fixture.device.connectGatt(fixture.context, false, any(), BluetoothDevice.TRANSPORT_LE)
        } throws SecurityException("revoked")
        val run = collectErrors(newClient(fixture))
        runCurrent()
        fixture.scanCallback.onScanResult(0, fixture.scanResult)
        runCurrent()
        assertTrue(run.message(), run.message().contains("connectGatt failed: revoked"))
    }

    @Test
    fun connectGattReturningNullIsReported() = runTest {
        val fixture = Fixture()
        every {
            fixture.device.connectGatt(fixture.context, false, any(), BluetoothDevice.TRANSPORT_LE)
        } returns null
        val run = collectErrors(newClient(fixture))
        runCurrent()
        fixture.scanCallback.onScanResult(0, fixture.scanResult)
        runCurrent()
        assertTrue(run.message(), run.message().contains("connectGatt returned null"))
    }

    @Test
    fun startScanThrowingIsReported() = runTest {
        val fixture = Fixture()
        every { fixture.scanner.startScan(any(), any(), any<ScanCallback>()) } throws SecurityException("no scan")
        val run = collectErrors(newClient(fixture))
        runCurrent()
        assertTrue(run.error is SecurityException)
        assertEquals("no scan", run.error?.message)
    }

    @Test
    fun scanIsHeldBackWhileTheThrottleBudgetIsSpent() = runTest {
        val fixture = Fixture()
        val throttle = BleScanThrottle(maxStarts = 1, now = { 0L })
        throttle.reserve() // Budget used up: the next reservation must wait a full window.
        collectErrors(newClient(fixture, throttle = throttle))
        runCurrent()
        verify(exactly = 0) { fixture.scanner.startScan(any(), any(), any<ScanCallback>()) }

        advanceTimeBy(BleScanThrottle.WINDOW_MILLIS + 1)
        runCurrent()
        verify(exactly = 1) { fixture.scanner.startScan(any(), any(), any<ScanCallback>()) }
    }

    @Test
    fun aLinkThatIsUpButNeverSubscribesIsRestartedAfterFourSecondsNotTen() = runTest {
        val fixture = Fixture()
        val run = collectErrors(newClient(fixture))
        runCurrent()
        fixture.scanCallback.onScanResult(0, fixture.scanResult)
        fixture.gattCallback.onConnectionStateChange(
            fixture.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
        )
        runCurrent()
        // Service discovery never answers.

        shadowOf(Looper.getMainLooper()).idleFor(3_500, TimeUnit.MILLISECONDS)
        advanceTimeBy(3_500)
        runCurrent()
        assertNull("still within the 4 s that a healthy link needs well under 1 s of", run.error)

        shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("bridge subscribe timeout", run.message())
        verify(exactly = 1) { fixture.gatt.close() }
    }

    @Test
    fun aLinkThatSubscribesInTimeIsNeverRestarted() = runTest {
        val fixture = Fixture()
        val run = collectErrors(newClient(fixture))
        runCurrent()
        connectAndDiscover(fixture)
        fixture.gattCallback.onDescriptorWrite(fixture.gatt, fixture.cccd, BluetoothGatt.GATT_SUCCESS)
        runCurrent()

        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        advanceTimeBy(30_000)
        runCurrent()

        assertNull(run.error)
        run.job.cancelAndJoin()
    }

    @Test
    fun teardownSurvivesABluetoothStackThatThrows() = runTest {
        val fixture = Fixture()
        every { fixture.scanner.stopScan(any<ScanCallback>()) } throws IllegalStateException("scanner gone")
        every { fixture.gatt.disconnect() } throws IllegalStateException("gatt gone")
        every { fixture.gatt.close() } throws IllegalStateException("gatt gone")
        val run = collectErrors(newClient(fixture))
        runCurrent()
        fixture.scanCallback.onScanResult(0, fixture.scanResult)
        fixture.gattCallback.onConnectionStateChange(
            fixture.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
        )

        run.job.cancelAndJoin()

        assertNull(run.error)
        verify(atLeast = 1) { fixture.scanner.stopScan(any<ScanCallback>()) }
        verify(exactly = 1) { fixture.gatt.disconnect() }
        verify(exactly = 1) { fixture.gatt.close() }
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
        val handshake = BluetoothGattCharacteristic(
            BridgeProtocol.HANDSHAKE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val cccd = BluetoothGattDescriptor(BridgeProtocol.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE)
        private val service = BluetoothGattService(
            BridgeProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        fun addHudProfile() = BluetoothGattCharacteristic(
            BridgeProtocol.HUD_PROFILE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).also(service::addCharacteristic)
        private val scanSlot = slot<ScanCallback>()
        private val gattSlot = slot<BluetoothGattCallback>()
        val scanCallback: ScanCallback get() = scanSlot.captured
        val gattCallback: BluetoothGattCallback get() = gattSlot.captured

        init {
            telemetry.addDescriptor(cccd)
            service.addCharacteristic(telemetry)
            service.addCharacteristic(handshake)
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
            if (Build.VERSION.SDK_INT >= 33) {
                every {
                    gatt.writeCharacteristic(
                        handshake, any(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    )
                } returns BluetoothStatusCodes.SUCCESS
                every {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } returns BluetoothGatt.GATT_SUCCESS
            } else {
                every { gatt.writeCharacteristic(handshake) } returns true
                every { gatt.writeDescriptor(cccd) } returns true
            }
        }
    }
}
