/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.telemetry.BmsFrame
import com.rideflux.domain.telemetry.WheelTelemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BegodeRawBmsConnectionTest {
    private val raw = BmsFrame.Raw(1, 0, List(24) { 0.toByte() })

    @Test fun `raw frames reach telemetry without satisfying handshake watchdog`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec(onDecode = { listOf(DecodeEvent.RawBmsFrame(raw)) })
        val connection = WheelConnectionImpl(transport, codec, backgroundScope)
        connection.start()
        runCurrent()
        transport.emit(byteArrayOf(1))
        runCurrent()
        assertEquals(raw, connection.telemetry.value.bmsFrame)
        assertEquals(0L, connection.telemetry.value.timestampMillis)
        assertNull(connection.telemetry.value.speedKmh)
        assertNull(connection.identity.value)
        advanceTimeBy(15_001L)
        runCurrent()
        assertEquals(ConnectionState.Failed.Reason.HANDSHAKE_TIMEOUT,
            (connection.state.value as ConnectionState.Failed).reason)
    }

    @Test fun `raw frames preserve freshness and cannot supply stationary samples`() = runTest {
        val transport = FakeBleTransport()
        val codec = FakeWheelCodec(
            onDecode = { bytes ->
                if (bytes[0] == 0.toByte()) {
                    listOf(DecodeEvent.TelemetryUpdate(WheelTelemetry(100, speedKmh = 0f)))
                } else listOf(DecodeEvent.RawBmsFrame(raw))
            },
            onEncode = { listOf(byteArrayOf(0x33)) },
        )
        val connection = WheelConnectionImpl(transport, codec, backgroundScope, clock = { 100 })
        connection.start()
        runCurrent()
        transport.emit(byteArrayOf(0))
        runCurrent()
        repeat(3) { transport.emit(byteArrayOf(1)); runCurrent() }
        assertEquals(100L, connection.telemetry.value.timestampMillis)
        assertTrue(connection.dispatch(WheelCommand.PowerOff) is CommandOutcome.InvalidArgument)
        assertTrue(transport.writes.isEmpty())
        transport.emit(byteArrayOf(0))
        runCurrent()
        assertEquals(raw, connection.telemetry.value.bmsFrame)
        connection.close()
        assertNull(connection.telemetry.value.bmsFrame)
    }
}
