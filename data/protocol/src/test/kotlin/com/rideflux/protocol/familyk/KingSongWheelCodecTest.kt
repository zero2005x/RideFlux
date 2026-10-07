/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyk

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.telemetry.ChargingState
import com.rideflux.domain.wheel.WheelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KingSongWheelCodecTest {

    private fun livePageA(
        voltageHundredths: Int = 8400,
        speedHundredths: Int = 2500,
        totalDistanceMeters: Long = 12345L,
        currentHundredths: Int = 500,
        tempHundredths: Int = 3500,
    ): ByteArray {
        val bytes = ByteArray(20)
        bytes[0] = 0xAA.toByte()
        bytes[1] = 0x55
        // Voltage u16LE @2
        bytes[2] = (voltageHundredths and 0xFF).toByte()
        bytes[3] = ((voltageHundredths ushr 8) and 0xFF).toByte()
        // Speed u16LE @4
        bytes[4] = (speedHundredths and 0xFF).toByte()
        bytes[5] = ((speedHundredths ushr 8) and 0xFF).toByte()
        // Total distance u32LE @6
        bytes[6] = (totalDistanceMeters and 0xFF).toByte()
        bytes[7] = ((totalDistanceMeters ushr 8) and 0xFF).toByte()
        bytes[8] = ((totalDistanceMeters ushr 16) and 0xFF).toByte()
        bytes[9] = ((totalDistanceMeters ushr 24) and 0xFF).toByte()
        // Current s16LE @10
        bytes[10] = (currentHundredths and 0xFF).toByte()
        bytes[11] = ((currentHundredths ushr 8) and 0xFF).toByte()
        // Temp u16LE @12
        bytes[12] = (tempHundredths and 0xFF).toByte()
        bytes[13] = ((tempHundredths ushr 8) and 0xFF).toByte()
        // Mode marker @15
        bytes[14] = 1
        bytes[15] = 0xE0.toByte()
        // Page @16
        bytes[16] = 0xA9.toByte()
        bytes[17] = 0x00
        // Tail @18, 19
        bytes[18] = 0x5A
        bytes[19] = 0x5A
        return bytes
    }

    private fun livePageB(
        tripMeters: Long = 500L,
        charging: Boolean = true,
        tempHundredths: Int = 4000,
    ): ByteArray {
        val bytes = ByteArray(20)
        bytes[0] = 0xAA.toByte()
        bytes[1] = 0x55
        // Trip distance u32LE @2
        bytes[2] = (tripMeters and 0xFF).toByte()
        bytes[3] = ((tripMeters ushr 8) and 0xFF).toByte()
        bytes[4] = ((tripMeters ushr 16) and 0xFF).toByte()
        bytes[5] = ((tripMeters ushr 24) and 0xFF).toByte()
        // Top speed @8
        bytes[8] = 0x00
        bytes[9] = 0x00
        // Fan @12
        bytes[12] = 0x01
        // Charging @13
        bytes[13] = if (charging) 1 else 0
        // Board temp @14
        bytes[14] = (tempHundredths and 0xFF).toByte()
        bytes[15] = ((tempHundredths ushr 8) and 0xFF).toByte()
        // Page @16
        bytes[16] = 0xB9.toByte()
        bytes[17] = 0x00
        // Tail @18, 19
        bytes[18] = 0x5A
        bytes[19] = 0x5A
        return bytes
    }

    @Test
    fun `decodes page A and page B merging telemetry and identifying device`() {
        val codec = KingSongWheelCodec(deviceAddress = "AA:BB:CC:DD:EE:FF")
        val state = codec.newState() as KingSongWheelCodec.KingSongState

        val eventsA = codec.decode(state, livePageA())
        assertEquals(2, eventsA.size)
        val identified = eventsA.filterIsInstance<DecodeEvent.Identified>().single()
        assertEquals("AA:BB:CC:DD:EE:FF", identified.identity.address)
        assertEquals(WheelFamily.K, identified.identity.family)

        val telemA = eventsA.filterIsInstance<DecodeEvent.TelemetryUpdate>().single().snapshot
        assertEquals(84.0f, telemA.voltageV)
        assertEquals(25.0f, telemA.speedKmh)
        assertEquals(5.0f, telemA.currentA)
        assertEquals(35.0f, telemA.mosTemperatureC)
        assertEquals(12345L, telemA.totalDistanceMetres)

        val eventsB = codec.decode(state, livePageB(tripMeters = 750L, charging = true))
        val telemB = eventsB.filterIsInstance<DecodeEvent.TelemetryUpdate>().single().snapshot
        assertEquals(750, telemB.tripDistanceMetres)
        assertEquals(ChargingState.CHARGING, telemB.chargingState)
        assertEquals(40.0f, telemB.boardTemperatureC)
        // Previous fields from Page A are preserved in rolling snapshot
        assertEquals(84.0f, telemB.voltageV)
        assertEquals(25.0f, telemB.speedKmh)

        assertEquals(2L, state.framesDecoded)
        assertEquals(0L, state.framesRejected)
        assertEquals(KingSongDecoder.CMD_LIVE_PAGE_B, state.lastPageSeen)
    }

    @Test
    fun `safe blank address does not crash Identified event`() {
        val codec = KingSongWheelCodec() // blank address default
        val state = codec.newState()
        val events = codec.decode(state, livePageA())
        val identified = events.filterIsInstance<DecodeEvent.Identified>().single()
        assertTrue(identified.identity.address.isNotBlank())
    }

    @Test
    fun `rejects AA55 frame with non-5A5A tail and emits Malformed event`() {
        val codec = KingSongWheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState() as KingSongWheelCodec.KingSongState

        val badTail = livePageA()
        badTail[18] = 0x12
        badTail[19] = 0x34

        val events = codec.decode(state, badTail)
        assertEquals(1, events.size)
        val malformed = events.single() as DecodeEvent.Malformed
        assertTrue(malformed.reason.contains("rejected"))
        assertNotNull(malformed.offendingBytes)
        assertEquals(1L, state.framesRejected)
        assertEquals(0L, state.framesDecoded)
    }

    @Test
    fun `rejects F1EF frame and emits Malformed event`() {
        val codec = KingSongWheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState() as KingSongWheelCodec.KingSongState

        val f1ef = ByteArray(20)
        f1ef[0] = 0xF1.toByte()
        f1ef[1] = 0xEF.toByte()
        f1ef[16] = 0xC2.toByte()

        val events = codec.decode(state, f1ef)
        val malformed = events.filterIsInstance<DecodeEvent.Malformed>().first()
        assertTrue(malformed.reason.contains("F1EF"))
        assertEquals(1L, state.framesRejected)
    }

    @Test
    fun `buffer overflow clears buffer and emits Malformed event`() {
        val codec = KingSongWheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState() as KingSongWheelCodec.KingSongState

        val noise = ByteArray(KingSongWheelCodec.MAX_BUFFER_BYTES + 10) { 0x77.toByte() }
        val events = codec.decode(state, noise)
        val malformed = events.filterIsInstance<DecodeEvent.Malformed>().single()
        assertTrue(malformed.reason.contains("overflow"))
        assertEquals(1L, state.framesRejected)
        assertTrue(state.buffer.isEmpty())
    }

    @Test
    fun `reassembles split frame across notification boundaries`() {
        val codec = KingSongWheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState() as KingSongWheelCodec.KingSongState

        val full = livePageA()
        val part1 = full.copyOfRange(0, 7)
        val part2 = full.copyOfRange(7, 20)

        val events1 = codec.decode(state, part1)
        assertTrue(events1.isEmpty())
        assertEquals(0L, state.framesDecoded)

        val events2 = codec.decode(state, part2)
        assertEquals(2, events2.size) // Identified + TelemetryUpdate
        assertEquals(1L, state.framesDecoded)
    }
}
