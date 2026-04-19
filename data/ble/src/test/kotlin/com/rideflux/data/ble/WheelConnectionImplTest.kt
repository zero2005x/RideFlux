/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.telemetry.ChargingState
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelFault
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WheelConnectionImplTest {

    private fun connection(
        transport: FakeBleTransport,
        codec: FakeWheelCodec,
        scope: CoroutineScope,
    ): WheelConnectionImpl =
        WheelConnectionImpl(
            transport = transport,
            codec = codec,
            scope = scope,
            clock = { 1_000L },
        )

    @Test
    fun `start connects transport and sends handshake frames`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec(
            handshake = listOf(byteArrayOf(0x01), byteArrayOf(0x02, 0x03)),
        )
        val conn = connection(transport, codec, backgroundScope)

        conn.start()
        runCurrent()

        assertEquals(1, transport.connectCount)
        assertEquals(2, transport.writes.size)
        assertArrayEquals(byteArrayOf(0x01), transport.writes[0])
        assertArrayEquals(byteArrayOf(0x02, 0x03), transport.writes[1])
        assertEquals(ConnectionState.Handshaking(WheelFamily.G), conn.state.value)
    }

    @Test
    fun `incoming bytes are fed into codec_decode`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec()
        val conn = connection(transport, codec, backgroundScope)

        conn.start()
        runCurrent()
        transport.emit(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        transport.emit(byteArrayOf(0xCC.toByte()))
        runCurrent()

        assertEquals(2, codec.decodeCalls.size)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), codec.decodeCalls[0])
        assertArrayEquals(byteArrayOf(0xCC.toByte()), codec.decodeCalls[1])
    }

    @Test
    fun `TelemetryUpdate events merge non-null fields into the snapshot`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec()
        val conn = connection(transport, codec, backgroundScope)

        codec.onDecode = { bytes ->
            when (bytes[0].toInt()) {
                1 -> listOf(
                    DecodeEvent.TelemetryUpdate(
                        WheelTelemetry(
                            timestampMillis = 100L,
                            voltageV = 84.0f,
                            speedKmh = 12.5f,
                        ),
                    ),
                )
                2 -> listOf(
                    DecodeEvent.TelemetryUpdate(
                        WheelTelemetry(
                            timestampMillis = 200L,
                            currentA = 4.2f,
                            chargingState = ChargingState.CHARGING,
                        ),
                    ),
                )
                else -> emptyList()
            }
        }

        conn.start()
        runCurrent()
        transport.emit(byteArrayOf(1))
        runCurrent()
        transport.emit(byteArrayOf(2))
        runCurrent()

        val snapshot = conn.telemetry.value
        assertEquals(200L, snapshot.timestampMillis)
        assertEquals(84.0f, snapshot.voltageV!!, 0.0001f)
        assertEquals(12.5f, snapshot.speedKmh!!, 0.0001f)
        assertEquals(4.2f, snapshot.currentA!!, 0.0001f)
        assertEquals(ChargingState.CHARGING, snapshot.chargingState)
    }

    @Test
    fun `Alert events are routed to the alerts SharedFlow`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec()
        val conn = connection(transport, codec, backgroundScope)

        val collected = mutableListOf<WheelAlert>()
        val job = backgroundScope.launch { conn.alerts.collect { collected.add(it) } }
        runCurrent()

        codec.onDecode = {
            listOf(
                DecodeEvent.Alert(
                    WheelAlert.TiltBack(
                        timestampMillis = 500L,
                        speedKmh = 30f,
                        limit = 25f,
                    ),
                ),
            )
        }

        conn.start()
        runCurrent()
        transport.emit(byteArrayOf(0x01))
        runCurrent()

        assertEquals(1, collected.size)
        val alert = collected.first() as WheelAlert.TiltBack
        assertEquals(30f, alert.speedKmh, 0.0001f)
        assertEquals(25f, alert.limit, 0.0001f)

        job.cancel()
    }

    @Test
    fun `Identified event populates identity, capabilities and flips state to Ready`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec()
        val conn = connection(transport, codec, backgroundScope)

        val identity = WheelIdentity(
            address = "AA:BB:CC:DD:EE:FF",
            family = WheelFamily.G,
            modelName = "MTen 4",
            firmwareVersion = "1.23",
        )
        val caps = capabilitiesStub()
        codec.onDecode = { listOf(DecodeEvent.Identified(identity, caps)) }

        conn.start()
        runCurrent()
        transport.emit(byteArrayOf(0x01))
        runCurrent()

        assertEquals(identity, conn.identity.value)
        assertSame(caps, conn.capabilities.value)
        assertEquals(ConnectionState.Ready, conn.state.value)
    }

    @Test
    fun `keep-alive loop writes periodic frames while connection is open`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec(keepAlivePeriodMillis = 25L)
        codec.onKeepAlive = { listOf(byteArrayOf(0x55)) }
        val conn = connection(transport, codec, backgroundScope)

        conn.start()
        runCurrent()
        // Clear any handshake writes.
        transport.writes.clear()

        advanceTimeBy(26L)  // first tick
        runCurrent()
        advanceTimeBy(25L)  // second tick
        runCurrent()
        advanceTimeBy(25L)  // third tick
        runCurrent()

        assertEquals(3, transport.writes.size)
        transport.writes.forEach { assertArrayEquals(byteArrayOf(0x55), it) }

        conn.close()
    }

    @Test
    fun `keep-alive is not started when codec reports period 0`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec(keepAlivePeriodMillis = 0L)
        codec.onKeepAlive = { listOf(byteArrayOf(0x77)) }
        val conn = connection(transport, codec, backgroundScope)

        conn.start()
        runCurrent()
        transport.writes.clear()

        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(
            "no keep-alive writes expected; got ${transport.writes.size}",
            transport.writes.isEmpty(),
        )

        conn.close()
    }

    @Test
    fun `dispatch encodes command and writes each frame to the transport`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec()
        codec.onEncode = { cmd ->
            if (cmd is WheelCommand.SetHeadlight) {
                listOf(byteArrayOf(0x10, if (cmd.on) 0x01 else 0x00))
            } else emptyList()
        }
        val conn = connection(transport, codec, backgroundScope)
        conn.start()
        runCurrent()
        transport.writes.clear()

        val outcome = conn.dispatch(WheelCommand.SetHeadlight(on = true))

        assertEquals(CommandOutcome.Success, outcome)
        assertEquals(1, codec.encodeCalls.size)
        assertEquals(1, transport.writes.size)
        assertArrayEquals(byteArrayOf(0x10, 0x01), transport.writes[0])
    }

    @Test
    fun `dispatch returns Unsupported when codec produces no frames`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec()  // onEncode defaults to emptyList()
        val conn = connection(transport, codec, backgroundScope)
        conn.start()
        runCurrent()
        transport.writes.clear()

        val outcome = conn.dispatch(WheelCommand.Horn)

        assertTrue(outcome is CommandOutcome.Unsupported)
        assertEquals(WheelCommand.Horn, (outcome as CommandOutcome.Unsupported).command)
        assertTrue(transport.writes.isEmpty())
    }

    @Test
    fun `dispatch surfaces transport failure as TransportError`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec()
        codec.onEncode = { listOf(byteArrayOf(0x20)) }
        val conn = connection(transport, codec, backgroundScope)
        conn.start()
        runCurrent()
        transport.writes.clear()

        val failure = RuntimeException("gatt busy")
        transport.writeFailure = failure

        val outcome = conn.dispatch(WheelCommand.Beep)

        assertTrue(outcome is CommandOutcome.TransportError)
        val err = outcome as CommandOutcome.TransportError
        assertEquals(WheelCommand.Beep, err.command)
        assertSame(failure, err.cause)
    }

    @Test
    fun `start surfaces transport connect failure as Failed state`() = runTest {
        val transport = FakeBleTransport()
        transport.connectFailure = RuntimeException("link lost")
        val codec = FakeWheelCodec(handshake = listOf(byteArrayOf(0x01)))
        val conn = connection(transport, codec, backgroundScope)

        conn.start()
        runCurrent()

        val st = conn.state.value
        assertTrue("state was $st", st is ConnectionState.Failed)
        assertEquals(
            ConnectionState.Failed.Reason.BLE_LINK_LOST,
            (st as ConnectionState.Failed).reason,
        )
        assertTrue(transport.writes.isEmpty())
    }

    @Test
    fun `close cancels ingest and keep-alive and disconnects transport`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec(keepAlivePeriodMillis = 10L)
        codec.onKeepAlive = { listOf(byteArrayOf(0x55)) }
        val conn = connection(transport, codec, backgroundScope)

        conn.start()
        runCurrent()
        advanceTimeBy(11L)
        runCurrent()
        transport.writes.clear()
        codec.decodeCalls.clear()

        conn.close()
        runCurrent()

        // Further incoming bytes must not be decoded and further
        // timer ticks must not produce writes.
        transport.emit(byteArrayOf(0x01))
        advanceTimeBy(100L)
        runCurrent()

        assertTrue(codec.decodeCalls.isEmpty())
        assertTrue(transport.writes.isEmpty())
        assertEquals(1, transport.disconnectCount)
        assertEquals(ConnectionState.Disconnected, conn.state.value)
    }

    @Test
    fun `derived single-field flows track telemetry updates`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec()
        codec.onDecode = { _ ->
            listOf(
                DecodeEvent.TelemetryUpdate(
                    WheelTelemetry(
                        timestampMillis = 10L,
                        speedKmh = 17.0f,
                        voltageV = 82.1f,
                        batteryPercent = 55f,
                    ),
                ),
            )
        }
        val conn = connection(transport, codec, backgroundScope)

        conn.start()
        runCurrent()

        assertNull(conn.speedKmh.value)
        assertNull(conn.voltageV.value)

        transport.emit(byteArrayOf(0x01))
        runCurrent()

        assertEquals(17.0f, conn.speedKmh.value!!, 0.0001f)
        assertEquals(82.1f, conn.voltageV.value!!, 0.0001f)
        assertEquals(55f, conn.batteryPercent.value!!, 0.0001f)
    }

    @Test
    fun `faults set is replaced wholesale on each TelemetryUpdate`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec()
        codec.onDecode = { bytes ->
            when (bytes[0].toInt()) {
                1 -> listOf(
                    DecodeEvent.TelemetryUpdate(
                        WheelTelemetry(
                            timestampMillis = 10L,
                            faults = setOf(WheelFault.MosOverTemperature, WheelFault.LowBattery),
                        ),
                    ),
                )
                2 -> listOf(
                    DecodeEvent.TelemetryUpdate(
                        WheelTelemetry(timestampMillis = 20L, faults = emptySet()),
                    ),
                )
                else -> emptyList()
            }
        }
        val conn = connection(transport, codec, backgroundScope)
        conn.start()
        runCurrent()

        transport.emit(byteArrayOf(1))
        runCurrent()
        assertEquals(
            setOf<WheelFault>(WheelFault.MosOverTemperature, WheelFault.LowBattery),
            conn.telemetry.value.faults,
        )

        transport.emit(byteArrayOf(2))
        runCurrent()
        assertTrue(conn.telemetry.value.faults.isEmpty())
    }

    // ---- helpers -------------------------------------------------------

    private fun capabilitiesStub() = WheelCapabilities(
        headlight = true, horn = false, beep = true, ledStrip = false,
        decorativeLights = false, rideModes = false, maxSpeed = true,
        tiltback = true, pedalSensitivity = false, pedalHorizontal = false,
        calibration = true, powerOff = false, volume = false, playSound = false,
        pinUnlock = false, asyncAlerts = true,
    )

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}
