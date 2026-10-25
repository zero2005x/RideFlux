/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.transport.BleTransport
import com.rideflux.domain.wheel.WheelFamily
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminal-failure write gate (N-01).
 *
 * [ConnectionState.Failed] is terminal for the connection instance: publishing
 * it must retire the session in the same critical section that arbitrates
 * every physical write. Until then `dispatch` could encode and write a real
 * command frame for a session whose first bootstrap write had already failed,
 * because the teardown of the writable link was still in flight.
 *
 * These are positive product regressions: they assert what a failed session
 * must do, not that a defect first reproduces. [FailedSessionWriteGateTest]
 * runs the production resolver/codec/connection above a fake transport; no
 * Android BLE stack and no vehicle are involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailedSessionWriteGateTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val factory = WheelCodecFactoryImpl()
    private val resolver = WheelSessionResolver(factory)

    /**
     * Real discovery resolution for a KingSong table: the final codec is the
     * production KingSong codec, so the frame a leaked dispatch would put on
     * the wire is the real one.
     */
    private fun kingSongTable(): GattSnapshot = GattSnapshot(
        deviceName = "KS-18XL",
        services = mapOf(GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE1)),
    )

    private fun hex(frames: List<ByteArray>): String =
        frames.joinToString { bytes -> bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) } }

    /**
     * Rejects the first characteristic write and holds [disconnect] at a latch,
     * exactly like the observed failure: the product has already published
     * `Failed(BLE_LINK_LOST)` while the writable link is still up and the
     * transport teardown has not completed.
     */
    private class RejectFirstWriteTransport(
        override val gattSnapshot: GattSnapshot,
        override val resolution: WheelResolution,
    ) : NegotiatingTransport {
        private val channel = Channel<ByteArray>(64)
        override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
        private val rejectFirst = AtomicBoolean(true)
        @Volatile private var connected = false

        val disconnectEntered = CountDownLatch(1)
        val disconnectRelease = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<ByteArray>()

        override suspend fun connect() {
            connected = true
        }

        override suspend fun disconnect() {
            disconnectEntered.countDown()
            check(disconnectRelease.await(10, TimeUnit.SECONDS)) { "teardown latch timed out" }
            connected = false
        }

        override suspend fun write(bytes: ByteArray) {
            if (!connected) throw IOException("not connected")
            if (rejectFirst.compareAndSet(true, false)) {
                throw IOException("simulated first characteristic write status failure")
            }
            writes += bytes.copyOf()
        }
    }

    /**
     * The first accepted write tears the link down (the ingest loop observes
     * the closed notification stream) while the connection still holds a
     * published binding.
     */
    private class LinkLossOnFirstWriteTransport : BleTransport {
        private val channel = Channel<ByteArray>(64)
        override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
        private val connected = AtomicBoolean(false)
        val writes = CopyOnWriteArrayList<ByteArray>()

        override suspend fun connect() {
            connected.set(true)
        }

        override suspend fun disconnect() {
            connected.set(false)
        }

        override suspend fun write(bytes: ByteArray) {
            if (!connected.get()) throw IOException("not connected")
            writes += bytes.copyOf()
            if (writes.size == 1) channel.close(IOException("simulated gatt disconnect"))
        }
    }

    @Test
    fun `a failed bootstrap write rejects commands while teardown is pending`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val snapshot = kingSongTable()
        val resolution = resolver.resolve(SessionHints(), snapshot)
        assertEquals(WheelFamily.K, resolution.family)
        val transport = RejectFirstWriteTransport(snapshot, resolution)
        val conn = WheelConnectionImpl(
            transport, null, scope,
            codecForResolution = {
                factory.forFamilyWithAddress(requireNotNull(it.family), address)
            },
        )
        val start = scope.launch { conn.start() }
        try {
            assertTrue(
                "failure teardown was not reached",
                transport.disconnectEntered.await(10, TimeUnit.SECONDS),
            )
            val state = conn.state.value
            assertTrue("expected Failed, was $state", state is ConnectionState.Failed)
            assertEquals(
                ConnectionState.Failed.Reason.BLE_LINK_LOST,
                (state as ConnectionState.Failed).reason,
            )
            assertTrue("the rejected bootstrap must not be recorded", transport.writes.isEmpty())

            // The writable link still exists: only the terminal-failure gate
            // may keep this command off the wire.
            val outcome = conn.dispatch(WheelCommand.SetHeadlight(true))
            assertTrue(
                "Failed session accepted command: outcome=$outcome, " +
                    "writes=${hex(transport.writes)}; expected zero writes",
                transport.writes.isEmpty(),
            )
            assertTrue("Failed session returned $outcome", outcome is CommandOutcome.Unsupported)
            assertEquals(
                WheelCommand.SetHeadlight(true),
                (outcome as CommandOutcome.Unsupported).command,
            )
        } finally {
            transport.disconnectRelease.countDown()
            withTimeout(10_000) { start.join() }
            conn.close()
            scope.cancel()
        }
    }

    @Test
    fun `link loss in the middle of a multi-frame command stops the remaining frames`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = LinkLossOnFirstWriteTransport()
        val frames = listOf(byteArrayOf(0x21), byteArrayOf(0x22), byteArrayOf(0x23))
        val codec = FakeWheelCodec().apply {
            onEncode = { frames }
        }
        val failurePublished = CountDownLatch(1)
        lateinit var conn: WheelConnectionImpl
        conn = WheelConnectionImpl(
            transport = transport,
            codec = codec,
            scope = scope,
            clock = {
                // The clock is evaluated before every physical write. Once the
                // first frame has been accepted, wait for the ingest loop to
                // publish the terminal failure instead of letting the next
                // frame race the publication.
                if (transport.writes.isNotEmpty()) {
                    failurePublished.await(5, TimeUnit.SECONDS)
                }
                1_000L
            },
        )
        val observer = scope.launch {
            conn.state.first { it is ConnectionState.Failed }
            failurePublished.countDown()
        }
        try {
            conn.start()
            assertEquals(ConnectionState.Handshaking(WheelFamily.G), conn.state.value)

            val outcome = conn.dispatch(WheelCommand.SetHeadlight(true))
            assertTrue(
                "expected Unsupported after the link-loss publication, was $outcome",
                outcome is CommandOutcome.Unsupported,
            )
            assertEquals(
                "only the in-flight frame may reach the transport: ${hex(transport.writes)}",
                1,
                transport.writes.size,
            )
            assertArrayEquals(byteArrayOf(0x21), transport.writes.single())
            assertEquals(ConnectionState.Failed.Reason.BLE_LINK_LOST,
                (conn.state.value as ConnectionState.Failed).reason)
        } finally {
            observer.cancel()
            conn.close()
            scope.cancel()
        }
    }

    @Test
    fun `a handshake-timeout failure rejects later commands without writing`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec(
            handshake = listOf(byteArrayOf(0x01)),
            onEncode = { listOf(byteArrayOf(0x42)) },
        )
        val conn = WheelConnectionImpl(transport, codec, backgroundScope, clock = { 1_000L })
        conn.start()
        runCurrent()

        advanceTimeBy(15_001L)
        runCurrent()
        val state = conn.state.value
        assertTrue("expected Failed, was $state", state is ConnectionState.Failed)
        assertEquals(
            ConnectionState.Failed.Reason.HANDSHAKE_TIMEOUT,
            (state as ConnectionState.Failed).reason,
        )

        transport.writes.clear()
        val outcome = conn.dispatch(WheelCommand.SetHeadlight(true))
        assertTrue("Failed session returned $outcome", outcome is CommandOutcome.Unsupported)
        assertTrue(
            "writes after the handshake timeout: ${hex(transport.writes)}",
            transport.writes.isEmpty(),
        )
        conn.close()
    }

    @Test
    fun `a link-loss failure rejects later commands without writing`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec(onEncode = { listOf(byteArrayOf(0x42)) })
        val conn = WheelConnectionImpl(transport, codec, backgroundScope, clock = { 1_000L })
        conn.start()
        runCurrent()

        transport.emitFailure(IOException("gatt link dropped"))
        runCurrent()
        val state = conn.state.value
        assertTrue("expected Failed, was $state", state is ConnectionState.Failed)

        transport.writes.clear()
        val outcome = conn.dispatch(WheelCommand.SetHeadlight(true))
        assertTrue("Failed session returned $outcome", outcome is CommandOutcome.Unsupported)
        assertTrue(
            "writes after the link loss: ${hex(transport.writes)}",
            transport.writes.isEmpty(),
        )
        conn.close()
    }

    @Test
    fun `dispatch remains writable while Handshaking so bootstrap sessions still work`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec(
            handshake = listOf(byteArrayOf(0x01)),
            onEncode = { listOf(byteArrayOf(0x42)) },
        )
        val conn = WheelConnectionImpl(transport, codec, backgroundScope, clock = { 1_000L })
        conn.start()
        runCurrent()

        // The gate must exclude only terminal failure, not Handshaking: the
        // bootstrap and its answer must still be exchanged before Ready.
        assertEquals(ConnectionState.Handshaking(WheelFamily.G), conn.state.value)
        transport.writes.clear()
        assertEquals(CommandOutcome.Success, conn.dispatch(WheelCommand.SetHeadlight(true)))
        assertArrayEquals(byteArrayOf(0x42), transport.writes.single())
        conn.close()
    }
}
