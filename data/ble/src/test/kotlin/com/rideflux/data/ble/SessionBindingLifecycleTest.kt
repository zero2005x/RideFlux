/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.wheel.WheelFamily
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session-binding lifecycle regressions (C-02/C-03).
 *
 * These are positive product tests: every assertion states the contract the
 * connection must honour, and none requires the defect to reproduce first.
 * Latches pin the event order; real resolver/codec/connection code runs above a
 * writable fake transport. No Android BLE stack, no vehicle.
 *
 *  * before the final binding exists, `dispatch` is unavailable and writes
 *    nothing — a provisional codec must never put another family's frame on
 *    the wire;
 *  * after the binding exists, the same command uses the bound family;
 *  * a `close()` that completes while the codec factory, `connect()` or a
 *    bootstrap write is in flight leaves no codec/state/family behind, starts
 *    no new work and writes no further frame.
 */
class SessionBindingLifecycleTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val factory = WheelCodecFactoryImpl()
    private val resolver = WheelSessionResolver(factory)

    private fun kingSongTable(): GattSnapshot = GattSnapshot(
        deviceName = "KS-18XL",
        services = mapOf(GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE1)),
    )

    /**
     * Writable [NegotiatingTransport] whose connect/write steps can be held at
     * a latch. `connect` mirrors the production contract: an unusable
     * resolution fails the link instead of letting a guessed family through.
     */
    private class LatchingTransport(
        override val gattSnapshot: GattSnapshot?,
        override val resolution: WheelResolution?,
    ) : NegotiatingTransport {
        private val incomingChannel = Channel<ByteArray>(64)
        override val incoming: Flow<ByteArray> = incomingChannel.receiveAsFlow()

        @Volatile private var connected = false
        val writes = CopyOnWriteArrayList<ByteArray>()

        var connectEntered: CountDownLatch? = null
        var connectRelease: CountDownLatch? = null
        var writeEntered: CountDownLatch? = null
        var writeRelease: CountDownLatch? = null

        override suspend fun connect() {
            connectEntered?.countDown()
            connectRelease?.await(10, TimeUnit.SECONDS)
            val resolved = resolution
            if (resolved != null && !resolved.usable) throw FamilyResolutionException(resolved)
            connected = true
        }

        override suspend fun disconnect() {
            connected = false
        }

        override suspend fun write(bytes: ByteArray) {
            writeEntered?.countDown()
            writeRelease?.await(10, TimeUnit.SECONDS)
            if (!connected) throw IOException("transport closed")
            writes += bytes.copyOf()
        }
    }

    private fun kingSongBootstrap(): List<ByteArray> {
        val codec = factory.forFamilyWithAddress(WheelFamily.K, address)
        return codec.handshakeFrames(codec.newState())
    }

    private fun hex(frames: List<ByteArray>): String =
        frames.joinToString { bytes -> bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) } }

    @Test
    fun `dispatch before the final binding is unavailable and writes nothing`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val snapshot = kingSongTable()
        // A saved Inmotion profile contradicts the discovered KingSong table:
        // discovery decides K, so the provisional I2 codec must stay unused.
        val resolution = resolver.resolve(
            SessionHints(savedFamily = WheelFamily.I2, advertisedName = "KS-18XL"),
            snapshot,
        )
        assertEquals(WheelFamily.K, resolution.family)
        val transport = LatchingTransport(snapshot, resolution)
        val provisionalI2 = factory.forFamilyWithAddress(WheelFamily.I2, address)
        // The frame the old code wrote to the KingSong transport before the
        // final binding (observed in the audit reproduction). Build it from
        // the command builder directly: an unidentified I2 codec now
        // deliberately emits nothing under the support contract.
        val i2Headlight = com.rideflux.protocol.familyi2.InmotionI2CommandBuilder
            .setHeadlight(true)
        val conn = WheelConnectionImpl(
            transport, provisionalI2, scope,
            codecForResolution = { resolved ->
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "factory latch timed out" }
                factory.forFamilyWithAddress(requireNotNull(resolved.family), address)
            },
        )
        val start = scope.launch { conn.start() }
        try {
            assertTrue("codec factory was never entered", entered.await(10, TimeUnit.SECONDS))

            // The harm first: nothing may reach the transport for a session
            // whose family discovery has already contradicted.
            val outcome = conn.dispatch(WheelCommand.SetHeadlight(true))
            assertEquals(
                "the provisional codec's headlight frame must be the known I2 one",
                "aaaa140360500126",
                hex(listOf(i2Headlight)),
            )
            assertTrue(
                "dispatch before the final binding wrote ${hex(transport.writes)}",
                transport.writes.isEmpty(),
            )
            assertTrue("dispatch was $outcome", outcome is CommandOutcome.Unsupported)
            assertEquals(
                WheelCommand.SetHeadlight(true),
                (outcome as CommandOutcome.Unsupported).command,
            )

            // …and no provisional identity may be observable either.
            assertNull("no provisional family may be visible", conn.resolvedFamily)
            assertNull("no provisional resolution may be visible", conn.resolution)
            assertTrue(
                "the codec getter must not hand out the provisional codec",
                runCatching { conn.codec }.exceptionOrNull() is IllegalStateException,
            )
        } finally {
            release.countDown()
            withTimeout(10_000) { start.join() }
        }

        // Positive half: the final binding is KingSong and it is what reaches
        // the wire afterwards.
        assertEquals(WheelFamily.K, conn.resolvedFamily)
        assertEquals(
            "only the KingSong bootstrap may be written",
            hex(kingSongBootstrap()),
            hex(transport.writes),
        )
        assertEquals(CommandOutcome.Success, conn.dispatch(WheelCommand.SetHeadlight(true)))
        val boundK = factory.forFamilyWithAddress(WheelFamily.K, address)
        val expectedK = boundK.encode(boundK.newState(), WheelCommand.SetHeadlight(true)).single()
        assertArrayEquals(expectedK, transport.writes.last())
        assertFalse(
            "the I2 provisional frame must never be written",
            transport.writes.any { it.contentEquals(i2Headlight) },
        )

        conn.close()
        scope.cancel()
    }

    @Test
    fun `close while the codec factory runs never republishes the binding`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val snapshot = kingSongTable()
        val resolution = resolver.resolve(SessionHints(), snapshot)
        assertEquals(WheelFamily.K, resolution.family)
        val transport = LatchingTransport(snapshot, resolution)
        val conn = WheelConnectionImpl(
            transport, null, scope,
            codecForResolution = {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "factory latch timed out" }
                factory.forFamilyWithAddress(WheelFamily.K, address)
            },
        )
        val start = scope.launch { conn.start() }
        try {
            assertTrue("codec factory was never entered", entered.await(10, TimeUnit.SECONDS))
            conn.close()
            assertEquals(ConnectionState.Disconnected, conn.state.value)
            assertNull("close must clear the family", conn.resolvedFamily)
            assertNull("close must clear the resolution", conn.resolution)

            // Let the factory finish after close() has returned. It must not
            // publish a codec, family or resolution, start a job, or write.
            release.countDown()
            withTimeout(10_000) { start.join() }

            assertEquals(ConnectionState.Disconnected, conn.state.value)
            assertNull("a retired binding was republished", conn.resolvedFamily)
            assertNull("a retired resolution was republished", conn.resolution)
            assertTrue("no frame may be written after close", transport.writes.isEmpty())
        } finally {
            release.countDown()
            withTimeout(10_000) { start.join() }
            conn.close()
            scope.cancel()
        }
    }

    @Test
    fun `close while connect is in flight publishes nothing`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val snapshot = kingSongTable()
        val resolution = resolver.resolve(SessionHints(), snapshot)
        val transport = LatchingTransport(snapshot, resolution).apply {
            connectEntered = CountDownLatch(1)
            connectRelease = CountDownLatch(1)
        }
        val conn = WheelConnectionImpl(
            transport, null, scope,
            codecForResolution = { factory.forFamilyWithAddress(WheelFamily.K, address) },
        )
        val start = scope.launch { conn.start() }
        try {
            assertTrue("connect was never entered", requireNotNull(transport.connectEntered).await(10, TimeUnit.SECONDS))
            conn.close()
            transport.connectRelease?.countDown()
            withTimeout(10_000) { start.join() }

            assertEquals(ConnectionState.Disconnected, conn.state.value)
            assertNull(conn.resolvedFamily)
            assertNull(conn.resolution)
            assertTrue(
                "a close during connect must not write: ${hex(transport.writes)}",
                transport.writes.isEmpty(),
            )
        } finally {
            transport.connectRelease?.countDown()
            withTimeout(10_000) { start.join() }
            conn.close()
            scope.cancel()
        }
    }

    @Test
    fun `close during the bootstrap stops the remaining handshake frames`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = LatchingTransport(gattSnapshot = null, resolution = null).apply {
            writeEntered = CountDownLatch(1)
            writeRelease = CountDownLatch(1)
        }
        val conn = WheelConnectionImpl(
            transport,
            factory.forFamilyWithAddress(WheelFamily.K, address),
            scope,
        )
        val start = scope.launch { conn.start() }
        try {
            assertTrue(
                "the first bootstrap frame was never written",
                requireNotNull(transport.writeEntered).await(10, TimeUnit.SECONDS),
            )
            // close() waits for the in-flight write (both hold the session
            // lock), so it can only finish after the release below.
            val closer = scope.launch { conn.close() }
            transport.writeRelease?.countDown()
            withTimeout(10_000) { closer.join() }

            val writesWhenClosed = transport.writes.size
            withTimeout(10_000) { start.join() }
            assertEquals(
                "no handshake frame may be written after close returned",
                writesWhenClosed,
                transport.writes.size,
            )
            assertEquals(ConnectionState.Disconnected, conn.state.value)
            assertNull(conn.resolvedFamily)
            assertTrue("some bootstrap frame must have gone out first", writesWhenClosed >= 1)
            assertEquals(
                "the first frame must be the KingSong bootstrap",
                hex(listOf(kingSongBootstrap().first())),
                hex(transport.writes.take(1)),
            )
        } finally {
            transport.writeRelease?.countDown()
            withTimeout(10_000) { start.join() }
            conn.close()
            scope.cancel()
        }
    }
}
