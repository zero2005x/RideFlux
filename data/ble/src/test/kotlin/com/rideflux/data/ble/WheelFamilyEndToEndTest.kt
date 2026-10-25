/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.protocol.familyvesc.VescSetupCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end offline acceptance through the production wiring:
 *
 * `discovery table → WheelSessionResolver → WheelConnectionImpl → real codec
 *  → bootstrap write → notification bytes → telemetry / Ready`.
 *
 * The transport is the narrow fake at [NegotiatingTransport]; everything below
 * it (resolver, codec factory, codecs, connection state machine, telemetry
 * merge) is production code. Android's GATT stack is not involved, so these
 * are **software-tested** results, not vehicle verification.
 */
class WheelFamilyEndToEndTest {

    private val address = "AA:BB:CC:DD:EE:FF"
    private val factory = WheelCodecFactoryImpl()
    private val resolver = WheelSessionResolver(factory)

    private fun singleChar(name: String? = null) = GattSnapshot(
        deviceName = name,
        services = mapOf(GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE1)),
    )

    private fun nus(name: String? = null) = GattSnapshot(
        deviceName = name,
        services = mapOf(
            GattUuids.SERVICE_NUS to listOf(GattUuids.CHAR_NUS_RX, GattUuids.CHAR_NUS_TX),
        ),
    )

    private fun split(name: String? = null) = GattSnapshot(
        deviceName = name,
        services = mapOf(
            GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE4),
            GattUuids.SERVICE_FFE5 to listOf(GattUuids.CHAR_FFE9),
        ),
    )

    @Test
    fun `Ready is reached only after the codec identifies, not on link-up`() = runTest {
        val t = transport(singleChar(), SessionHints(advertisedName = "KS-18XL"))
        val conn = connection(t, backgroundScope)

        assertEquals(ConnectionState.Disconnected, conn.state.value)
        conn.start()
        runCurrent()

        // Link up + bootstrap done, but no identifying frame has arrived yet.
        assertEquals(1, t.connectCount)
        assertTrue("the bootstrap must be written before Ready", t.writes.isNotEmpty())
        assertEquals(ConnectionState.Handshaking(WheelFamily.K), conn.state.value)

        // A frame that does not identify (no live page yet) must not flip Ready.
        t.emit(byteArrayOf(0x00, 0x01, 0x02))
        runCurrent()
        assertEquals(ConnectionState.Handshaking(WheelFamily.K), conn.state.value)

        t.emit(kingSongPageA(voltageHundredths = 8400, speedHundredths = 2500))
        runCurrent()
        assertEquals(ConnectionState.Ready, conn.state.value)
    }

    @Test
    fun `a silent VESC session times out and stops polling`() = runTest {
        val t = transport(nus(), SessionHints(manualFamily = WheelFamily.VESC))
        val conn = connection(t, backgroundScope)
        conn.start()
        runCurrent()
        assertTrue("the poll must have been sent", t.writes.isNotEmpty())

        advanceTimeBy(15_000)
        runCurrent()
        val state = conn.state.value
        assertTrue("expected handshake timeout, got $state", state is ConnectionState.Failed)
        assertEquals(
            ConnectionState.Failed.Reason.HANDSHAKE_TIMEOUT,
            (state as ConnectionState.Failed).reason,
        )
        val writesAtTimeout = t.writes.size
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("polling must stop after the timeout", writesAtTimeout, t.writes.size)
    }

    private fun connection(
        transport: FakeNegotiatingTransport,
        scope: CoroutineScope,
    ) = WheelConnectionImpl(
        transport = transport,
        codec = null,
        scope = scope,
        clock = { 1_000L },
        codecForResolution = { resolution ->
            factory.forFamilyWithAddress(requireNotNull(resolution.family), address)
        },
    )

    private fun transport(
        snapshot: GattSnapshot,
        hints: SessionHints,
    ) = FakeNegotiatingTransport(snapshot) { resolver.resolve(hints, it) }

    // ---------------------------------------------------------------- KingSong

    @Test
    fun `KingSong FFE0 FFE1 resolves K, sends K bootstrap and publishes telemetry`() = runTest {
        val t = transport(singleChar(), SessionHints(advertisedName = "KS-18XL"))
        val conn = connection(t, backgroundScope)
        conn.start()

        assertEquals(WheelFamily.K, conn.resolvedFamily)
        assertEquals(ResolutionConfidence.GATT_PROBABLE, conn.resolution?.confidence)
        assertEquals(ConnectionState.Handshaking(WheelFamily.K), conn.state.value)
        assertTrue("KingSong bootstrap must not be empty", t.writes.isNotEmpty())
        val kingSongBootstrap = t.writes.toList()

        t.emit(kingSongPageA(voltageHundredths = 8400, speedHundredths = 2500))
        runCurrent()

        assertEquals(ConnectionState.Ready, conn.state.value)
        assertEquals(84.0f, conn.telemetry.value.voltageV)
        assertEquals(25.0f, conn.telemetry.value.speedKmh)
        assertEquals(12_345L, conn.telemetry.value.totalDistanceMetres)
        assertEquals(WheelFamily.K, conn.identity.value?.family)
    }

    // ----------------------------------------------------------------- Veteran

    @Test
    fun `Veteran FFE0 FFE1 resolves V, and is not decoded by the KingSong codec`() = runTest {
        val t = transport(singleChar(), SessionHints(advertisedName = "Sherman S"))
        val conn = connection(t, backgroundScope)
        conn.start()

        assertEquals(WheelFamily.V, conn.resolvedFamily)
        assertEquals(ConnectionState.Handshaking(WheelFamily.V), conn.state.value)
        // Family V auto-advertises: the K bootstrap (2 writes) must not appear.
        assertTrue("V must not send the KingSong bootstrap", t.writes.isEmpty())

        t.emit(veteranLegacyFrame())
        runCurrent()

        assertEquals(ConnectionState.Ready, conn.state.value)
        assertEquals(100.8f, conn.telemetry.value.voltageV)
        assertEquals(20.0f, conn.telemetry.value.speedKmh)
        assertEquals(WheelFamily.V, conn.identity.value?.family)
    }

    // ------------------------------------------------------- unresolved tables

    @Test
    fun `unknown FFE0 FFE1 fails as UNKNOWN_FAMILY and sends no bootstrap`() = runTest {
        val t = transport(singleChar(), SessionHints())
        val conn = connection(t, backgroundScope)
        conn.start()
        runCurrent()

        val state = conn.state.value
        assertTrue("expected Failed, got $state", state is ConnectionState.Failed)
        assertEquals(
            ConnectionState.Failed.Reason.UNKNOWN_FAMILY,
            (state as ConnectionState.Failed).reason,
        )
        assertTrue("no family may be bootstrapped", t.writes.isEmpty())
        assertTrue(t.disconnectCount >= 1)
    }

    @Test
    fun `unknown NUS peer is not routed to N2 I2 or VESC`() = runTest {
        val t = transport(nus(), SessionHints())
        val conn = connection(t, backgroundScope)
        conn.start()
        runCurrent()

        val state = conn.state.value as ConnectionState.Failed
        assertEquals(ConnectionState.Failed.Reason.UNKNOWN_FAMILY, state.reason)
        assertTrue("no VESC/N2/I2 frame may be written", t.writes.isEmpty())
    }

    @Test
    fun `manual selection that contradicts the table fails with a diagnostic`() = runTest {
        val t = transport(nus(), SessionHints(manualFamily = WheelFamily.G))
        val conn = connection(t, backgroundScope)
        conn.start()
        runCurrent()

        val state = conn.state.value as ConnectionState.Failed
        assertEquals(ConnectionState.Failed.Reason.UNKNOWN_FAMILY, state.reason)
        assertTrue(state.message.orEmpty().contains("manual selection G"))
        assertTrue(t.writes.isEmpty())
    }

    // -------------------------------------------------------------------- VESC

    @Test
    fun `VESC on NUS polls 0x2F only and publishes the setup telemetry`() = runTest {
        val t = transport(nus(), SessionHints(manualFamily = WheelFamily.VESC))
        val conn = connection(t, backgroundScope)
        conn.start()

        assertEquals(WheelFamily.VESC, conn.resolvedFamily)
        assertEquals(ConnectionState.Handshaking(WheelFamily.VESC), conn.state.value)
        assertEquals(1, t.writes.size)
        assertArrayEquals(VescSetupCodec.setupPoll(), t.writes.single())

        t.emit(vescSetupFrame())
        runCurrent()

        assertEquals(ConnectionState.Ready, conn.state.value)
        val telemetry = conn.telemetry.value
        assertEquals(84.0f, telemetry.voltageV)
        // The fixture's speed word is 25000, i.e. 25 m/s on the wire; the
        // domain publishes km/h, so the end-to-end value is 90 km/h.
        assertEquals(90.0f, telemetry.speedKmh)
        assertEquals(25.0f, telemetry.currentA)
        assertEquals(75.0f, telemetry.batteryPercent)
        assertEquals(45.0f, telemetry.mosTemperatureC)
        assertEquals(123_456L, telemetry.totalDistanceMetres)
        assertEquals(WheelFamily.VESC, conn.identity.value?.family)
        assertEquals("VESC", conn.identity.value?.modelName)
        // Read-only family: no setting command is encodable.
        assertTrue(t.writes.none { it.contentEquals(byteArrayOf(0x65)) })
    }

    @Test
    fun `VESC reassembles a setup frame split across notifications`() = runTest {
        val t = transport(nus(), SessionHints(manualFamily = WheelFamily.VESC))
        val conn = connection(t, backgroundScope)
        conn.start()

        val frame = vescSetupFrame()
        t.emit(frame.copyOfRange(0, 30))
        runCurrent()
        assertEquals(ConnectionState.Handshaking(WheelFamily.VESC), conn.state.value)

        t.emit(frame.copyOfRange(30, frame.size))
        runCurrent()
        assertEquals(ConnectionState.Ready, conn.state.value)
        assertEquals(84.0f, conn.telemetry.value.voltageV)
    }

    @Test
    fun `a VESC frame with a bad CRC is rejected without publishing telemetry`() = runTest {
        val t = transport(nus(), SessionHints(manualFamily = WheelFamily.VESC))
        val conn = connection(t, backgroundScope)
        conn.start()

        val frame = vescSetupFrame()
        frame[frame.size - 3] = (frame[frame.size - 3].toInt() xor 0xff).toByte()
        t.emit(frame)
        runCurrent()

        assertEquals(ConnectionState.Handshaking(WheelFamily.VESC), conn.state.value)
        assertNull(conn.telemetry.value.voltageV)
    }

    @Test
    fun `two KingSong frames in one notification both decode`() = runTest {
        val t = transport(singleChar(), SessionHints(advertisedName = "KS-18XL"))
        val conn = connection(t, backgroundScope)
        conn.start()

        val both = kingSongPageA(voltageHundredths = 8400, speedHundredths = 2500) +
            kingSongPageB(tripMeters = 750L)
        t.emit(both)
        runCurrent()

        assertEquals(ConnectionState.Ready, conn.state.value)
        assertEquals(84.0f, conn.telemetry.value.voltageV)
        assertEquals(750, conn.telemetry.value.tripDistanceMetres)
        assertEquals(40.0f, conn.telemetry.value.boardTemperatureC)
    }

    @Test
    fun `VESC ignores noise and unknown messages and resynchronises after truncation`() = runTest {
        val t = transport(nus(), SessionHints(manualFamily = WheelFamily.VESC))
        val conn = connection(t, backgroundScope)
        conn.start()

        // Noise, a truncated start, an unknown command (0x04 COMM_GET_VALUES), then a valid frame.
        t.emit(byteArrayOf(0x11, 0x22, 0x02, 0x46, 0x01, 0x02))
        t.emit(vescFrame(command = 0x04, payload = ByteArray(70) { 0x00 }))
        t.emit(vescSetupFrame())
        runCurrent()

        assertEquals(ConnectionState.Ready, conn.state.value)
        assertEquals(84.0f, conn.telemetry.value.voltageV)
    }

    // ------------------------------------------------------- existing NUS paths

    @Test
    fun `Inmotion and Ninebot names keep their own NUS families`() = runTest {
        val i2 = transport(nus(), SessionHints(advertisedName = "Inmotion V11"))
        val c2 = connection(i2, backgroundScope)
        c2.start()
        assertEquals(WheelFamily.I2, c2.resolvedFamily)

        val n2 = transport(nus(), SessionHints(advertisedName = "Ninebot Z10"))
        val cn = connection(n2, backgroundScope)
        cn.start()
        assertEquals(WheelFamily.N2, cn.resolvedFamily)
        assertFalse(
            "N2 must not send the VESC poll",
            n2.writes.any { it.contentEquals(VescSetupCodec.setupPoll()) },
        )
    }

    @Test
    fun `split FFE0 FFE5 resolves Inmotion I1`() = runTest {
        val t = transport(split(), SessionHints(advertisedName = "Inmotion V8F"))
        val conn = connection(t, backgroundScope)
        conn.start()
        assertEquals(WheelFamily.I1, conn.resolvedFamily)
        assertEquals(GattTopology.SPLIT_CHAR, conn.resolution?.topology)
    }

    @Test
    fun `a closed session cannot leak bytes or poll into the next session`() = runTest {
        val first = transport(nus(), SessionHints(manualFamily = WheelFamily.VESC))
        val c1 = connection(first, backgroundScope)
        c1.start()
        runCurrent()
        val firstWrites = first.writes.size
        assertTrue(firstWrites >= 1)

        c1.close()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("keep-alive must stop after close", firstWrites, first.writes.size)

        // Half of a frame delivered to the retired transport must not reach the
        // next connection, and the next connection starts with empty state.
        val frame = vescSetupFrame()
        first.emit(frame.copyOfRange(0, 30))
        runCurrent()

        val second = transport(nus(), SessionHints(manualFamily = WheelFamily.VESC))
        val c2 = connection(second, backgroundScope)
        c2.start()
        runCurrent()
        assertEquals(ConnectionState.Handshaking(WheelFamily.VESC), c2.state.value)
        assertNull(c2.telemetry.value.voltageV)

        second.emit(frame.copyOfRange(30, frame.size))
        runCurrent()
        // The second session never saw the first half, so this half alone cannot
        // complete a frame: it must stay unresolved instead of splicing sessions.
        assertEquals(ConnectionState.Handshaking(WheelFamily.VESC), c2.state.value)
    }

    // ------------------------------------------------------------------ frames

    /** Synthetic KingSong live page A; shape mirrors the protocol tests. */
    private fun kingSongPageA(voltageHundredths: Int, speedHundredths: Int): ByteArray {
        val bytes = ByteArray(20)
        bytes[0] = 0xAA.toByte()
        bytes[1] = 0x55
        bytes[2] = (voltageHundredths and 0xFF).toByte()
        bytes[3] = ((voltageHundredths ushr 8) and 0xFF).toByte()
        bytes[4] = (speedHundredths and 0xFF).toByte()
        bytes[5] = ((speedHundredths ushr 8) and 0xFF).toByte()
        val distance = 12_345
        bytes[6] = (distance and 0xFF).toByte()
        bytes[7] = ((distance ushr 8) and 0xFF).toByte()
        bytes[8] = ((distance ushr 16) and 0xFF).toByte()
        bytes[9] = ((distance ushr 24) and 0xFF).toByte()
        bytes[10] = 0x05
        bytes[11] = 0x00
        bytes[12] = 0xAC.toByte() // 3500 hundredths
        bytes[13] = 0x0D
        bytes[14] = 1
        bytes[15] = 0xE0.toByte()
        bytes[16] = 0xA9.toByte()
        bytes[18] = 0x5A
        bytes[19] = 0x5A
        return bytes
    }

    /** Synthetic Veteran legacy 36-byte frame (same shape as the protocol tests). */
    private fun veteranLegacyFrame(): ByteArray {
        val frame = ByteArray(36)
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A.toByte()
        frame[2] = 0x5C.toByte()
        frame[3] = 0x20.toByte()
        frame[4] = 0x27
        frame[5] = 0x60
        frame[6] = 0x00
        frame[7] = 0xC8.toByte()
        frame[16] = 0x05
        frame[17] = 0xDC.toByte()
        frame[18] = 0x0D
        frame[19] = 0xAC.toByte()
        frame[28] = 0x03
        frame[29] = 0xE8.toByte()
        frame[30] = 0x00
        frame[31] = 0x02
        frame[34] = 0x09
        frame[35] = 0xC4.toByte()
        return frame
    }

    /**
     * Synthetic COMM_GET_VALUES_SETUP reply (full mask, 70-byte payload).
     * Field offsets and scales follow the upstream handler; the values are
     * constructed here, not copied from any capture.
     */
    private fun kingSongPageB(tripMeters: Long): ByteArray {
        val bytes = ByteArray(20)
        bytes[0] = 0xAA.toByte()
        bytes[1] = 0x55
        bytes[2] = (tripMeters and 0xFF).toByte()
        bytes[3] = ((tripMeters ushr 8) and 0xFF).toByte()
        bytes[4] = ((tripMeters ushr 16) and 0xFF).toByte()
        bytes[5] = ((tripMeters ushr 24) and 0xFF).toByte()
        bytes[12] = 0x01
        bytes[13] = 0x01
        bytes[14] = 0xA0.toByte() // 4000 hundredths -> 40.0 C
        bytes[15] = 0x0F
        bytes[16] = 0xB9.toByte()
        bytes[18] = 0x5A
        bytes[19] = 0x5A
        return bytes
    }

    private fun vescSetupFrame(): ByteArray = vescFrame(command = 0x2F, payload = vescPayload())

    private fun vescPayload(): ByteArray {
        val payload = ByteArray(70)
        payload[0] = 0x2F
        put16(payload, 1, 450)          // temp_fet 45.0
        put32(payload, 5, 1234)         // current_tot 12.34
        put32(payload, 9, 2500)         // current_in 25.0
        put16(payload, 13, 500)         // duty 50.0 %
        put32(payload, 15, 3000)        // rpm
        put32(payload, 19, 25_000)      // speed 25.0 m/s (= 90 km/h)
        put16(payload, 23, 840)         // v_in 84.0
        put16(payload, 25, 750)         // battery 75.0 %
        put32(payload, 62, 123_456)     // odometer metres
        return payload
    }

    /** Wrap an arbitrary payload in VESC packet framing with a valid CRC. */
    private fun vescFrame(command: Int, payload: ByteArray): ByteArray {
        val body = payload.copyOf()
        body[0] = command.toByte()
        val crc = VescSetupCodec.crc16Xmodem(body)
        val frame = ByteArray(1 + 1 + payload.size + 3)
        frame[0] = 0x02
        frame[1] = payload.size.toByte()
        body.copyInto(frame, 2)
        frame[frame.size - 3] = ((crc ushr 8) and 0xff).toByte()
        frame[frame.size - 2] = (crc and 0xff).toByte()
        frame[frame.size - 1] = 0x03
        return frame
    }

    private fun put16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 8) and 0xff).toByte()
        target[offset + 1] = (value and 0xff).toByte()
    }

    private fun put32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xff).toByte()
        target[offset + 1] = ((value ushr 16) and 0xff).toByte()
        target[offset + 2] = ((value ushr 8) and 0xff).toByte()
        target[offset + 3] = (value and 0xff).toByte()
    }
}

/**
 * A [NegotiatingTransport] that plays a discovered table and then a byte
 * stream. It is the seam for the production connection/resolver wiring; the
 * Android GATT implementation is covered separately.
 */
internal class FakeNegotiatingTransport(
    private val snapshot: GattSnapshot? = null,
    private val resolve: ((GattSnapshot) -> WheelResolution)? = null,
) : NegotiatingTransport {

    private val channel = Channel<ByteArray>(capacity = 64)

    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()

    val writes = mutableListOf<ByteArray>()

    override var gattSnapshot: GattSnapshot? = null
        private set

    override var resolution: WheelResolution? = null
        private set

    var connectCount = 0
        private set

    var disconnectCount = 0
        private set

    override suspend fun connect() {
        connectCount++
        val discovered = snapshot ?: return
        gattSnapshot = discovered
        val resolved = requireNotNull(resolve) { "snapshot without resolver" }(discovered)
        resolution = resolved
        if (!resolved.usable) throw FamilyResolutionException(resolved)
    }

    override suspend fun disconnect() {
        disconnectCount++
    }

    override suspend fun write(bytes: ByteArray) {
        writes += bytes.copyOf()
    }

    suspend fun emit(bytes: ByteArray) {
        channel.send(bytes)
    }
}
