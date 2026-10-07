/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi2

import com.rideflux.domain.codec.DecodeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InmotionI2WheelCodecTest {

    private fun buildFrame(cmd: Int, data: ByteArray, flags: Int = InmotionI2Codec.FLAGS_DEFAULT): ByteArray {
        val len = data.size + 1
        val body = ByteArray(2 + data.size)
        body[0] = flags.toByte()
        body[1] = len.toByte()
        // cmd is byte 2 in body? Let's check InmotionI2CommandBuilder or InmotionI2Decoder!
        // InmotionI2Decoder: flags = body[0], len = body[1], cmd = body[2], data = body[3..]
        val fullBody = byteArrayOf(flags.toByte(), len.toByte(), cmd.toByte()) + data
        val escaped = InmotionI2Codec.escape(fullBody)
        val check = InmotionI2Codec.xorChecksum8(fullBody)
        return byteArrayOf(InmotionI2Codec.PREAMBLE_BYTE, InmotionI2Codec.PREAMBLE_BYTE) +
            escaped +
            byteArrayOf(check.toByte())
    }

    @Test
    fun `frame split across BLE chunks at trailing A5 does not drop bytes or corrupt frame`() {
        // Construct a frame containing escaped bytes (A5 AA and A5 A5)
        val data = byteArrayOf(0x01, 0xAA.toByte(), 0x02, 0xA5.toByte(), 0x03)
        val wire = buildFrame(cmd = 0x20, data = data)

        // Find the index of the first A5 escape byte
        val firstEscapeIdx = wire.indexOf(InmotionI2Codec.ESCAPE_BYTE)
        assertTrue("wire must contain escape byte", firstEscapeIdx >= 0)

        // Split exactly after the A5 byte (so chunk1 ends with A5)
        val splitPoint = firstEscapeIdx + 1
        val chunk1 = wire.copyOfRange(0, splitPoint)
        val chunk2 = wire.copyOfRange(splitPoint, wire.size)

        val codec = InmotionI2WheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State

        val events1 = codec.decode(state, chunk1)
        assertTrue("No events expected from partial frame", events1.isEmpty())
        assertEquals("Buffer should retain all chunk1 bytes", chunk1.size, state.buffer.size)

        val events2 = codec.decode(state, chunk2)
        // Verify buffer is empty and no malformed errors occurred
        assertEquals(0, state.buffer.size)
        assertFalse("No malformed errors should be produced", events2.any { it is DecodeEvent.Malformed })
    }

    @Test
    fun `malformed escape sequence produces DecodeEvent Malformed and resyncs`() {
        // Construct a wire frame and corrupt the byte after A5 to an invalid escape (e.g. 0x11)
        val data = byteArrayOf(0x01, 0xAA.toByte(), 0x02)
        val wire = buildFrame(cmd = 0x20, data = data)
        val escapeIdx = wire.indexOf(InmotionI2Codec.ESCAPE_BYTE)
        assertTrue(escapeIdx >= 0)
        wire[escapeIdx + 1] = 0x11 // Invalid: neither 0xAA nor 0xA5

        val codec = InmotionI2WheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState()
        val events = codec.decode(state, wire)

        assertTrue("Should produce Malformed event for invalid escape", events.any { it is DecodeEvent.Malformed })
    }

    @Test
    fun `binary version table parses mainboard version and gates V11 early layout`() {
        val codec = InmotionI2WheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State

        // 1. Send CarType = "V11"
        val carTypeData = byteArrayOf(InmotionI2CommandBuilder.MAIN_INFO_CAR_TYPE.toByte()) + "V11".toByteArray(Charsets.US_ASCII)
        val carTypeFrame = buildFrame(cmd = InmotionI2CommandBuilder.CMD_MAIN_INFO, data = carTypeData)
        val carTypeEvents = codec.decode(state, carTypeFrame)

        // Identified event emitted as soon as carType is known
        val identified = carTypeEvents.filterIsInstance<DecodeEvent.Identified>().firstOrNull()
        assertNotNull(identified)
        assertEquals("V11", identified!!.identity.modelName)
        assertNull(identified.identity.firmwareVersion)

        // 2. Send Realtime frame BEFORE version is known -> only universal 2-field core decoded
        val v11RealtimeData = ByteArray(56)
        // Voltage 84.00V = 8400 = 0x20D0
        v11RealtimeData[0] = 0xD0.toByte(); v11RealtimeData[1] = 0x20
        // Bus current 5.00A = 500 = 0x01F4
        v11RealtimeData[2] = 0xF4.toByte(); v11RealtimeData[3] = 0x01
        // Speed 20.00 km/h = 2000 = 0x07D0
        v11RealtimeData[4] = 0xD0.toByte(); v11RealtimeData[5] = 0x07
        // Trip 1000m -> 100 ten-metres = 0x0064
        v11RealtimeData[12] = 0x64; v11RealtimeData[13] = 0x00
        // Battery level 85% = 85
        v11RealtimeData[16] = 85
        // MOS temp 35°C -> raw = 35 + 176 = 211 = 0xD3
        v11RealtimeData[17] = 0xD3.toByte()

        val rtFrame = buildFrame(cmd = InmotionI2CommandBuilder.CMD_REALTIME, data = v11RealtimeData)
        val rtEvents1 = codec.decode(state, rtFrame)
        val telem1 = rtEvents1.filterIsInstance<DecodeEvent.TelemetryUpdate>().first().snapshot
        assertEquals(84.0f, telem1.voltageV)
        assertEquals(5.0f, telem1.currentA)
        // Speed, battery, etc. remain null because FW is not yet verified < 1.4
        assertNull(telem1.speedKmh)
        assertNull(telem1.batteryPercent)
        assertNull(telem1.phaseCurrentA)

        // 3. Send binary version response for FW 1.3.50 (MainBoard1=1, MainBoard2=3, MainBoard3=50)
        val verData = ByteArray(25)
        verData[0] = InmotionI2CommandBuilder.MAIN_INFO_VERSION.toByte()
        // MainBoard3 LE u16 at [11..12] = 50
        verData[11] = 50; verData[12] = 0
        // MainBoard2 at [13] = 3
        verData[13] = 3
        // MainBoard1 at [14] = 1
        verData[14] = 1

        val verFrame = buildFrame(cmd = InmotionI2CommandBuilder.CMD_MAIN_INFO, data = verData)
        val verEvents = codec.decode(state, verFrame)
        val updatedIdentified = verEvents.filterIsInstance<DecodeEvent.Identified>().firstOrNull()
        assertNotNull(updatedIdentified)
        assertEquals("1.3.50", updatedIdentified!!.identity.firmwareVersion)

        // 4. Send Realtime frame NOW -> FW < 1.4 is verified, so V11 early layout applies
        val rtEvents2 = codec.decode(state, rtFrame)
        val telem2 = rtEvents2.filterIsInstance<DecodeEvent.TelemetryUpdate>().first().snapshot
        assertEquals(84.0f, telem2.voltageV)
        assertEquals(5.0f, telem2.currentA)
        assertEquals(20.0f, telem2.speedKmh)
        assertEquals(1000, telem2.tripDistanceMetres)
        assertEquals(85.0f, telem2.batteryPercent)
        assertEquals(35.0f, telem2.mosTemperatureC)
        // phaseCurrentA must remain null (offset 2 is bus current, NOT phase current)
        assertNull(telem2.phaseCurrentA)
    }

    @Test
    fun `V11 with FW 1 4 does not apply early layout`() {
        val codec = InmotionI2WheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State

        // CarType = "V11"
        val carTypeData = byteArrayOf(InmotionI2CommandBuilder.MAIN_INFO_CAR_TYPE.toByte()) + "V11".toByteArray(Charsets.US_ASCII)
        codec.decode(state, buildFrame(cmd = InmotionI2CommandBuilder.CMD_MAIN_INFO, data = carTypeData))

        // FW 1.4.0 (MainBoard1=1, MainBoard2=4)
        val verData = ByteArray(25)
        verData[0] = InmotionI2CommandBuilder.MAIN_INFO_VERSION.toByte()
        verData[13] = 4
        verData[14] = 1
        codec.decode(state, buildFrame(cmd = InmotionI2CommandBuilder.CMD_MAIN_INFO, data = verData))

        val v11RealtimeData = ByteArray(56)
        v11RealtimeData[0] = 0xD0.toByte(); v11RealtimeData[1] = 0x20
        v11RealtimeData[2] = 0xF4.toByte(); v11RealtimeData[3] = 0x01
        v11RealtimeData[4] = 0xD0.toByte(); v11RealtimeData[5] = 0x07

        val events = codec.decode(state, buildFrame(cmd = InmotionI2CommandBuilder.CMD_REALTIME, data = v11RealtimeData))
        val telem = events.filterIsInstance<DecodeEvent.TelemetryUpdate>().first().snapshot

        assertEquals(84.0f, telem.voltageV)
        assertEquals(5.0f, telem.currentA)
        // Early layout is gated off for FW >= 1.4
        assertNull(telem.speedKmh)
    }

    @Test
    fun `V11Y does not match V11 early layout even with FW 1 3`() {
        val codec = InmotionI2WheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State

        // CarType = "V11Y"
        val carTypeData = byteArrayOf(InmotionI2CommandBuilder.MAIN_INFO_CAR_TYPE.toByte()) + "V11Y".toByteArray(Charsets.US_ASCII)
        codec.decode(state, buildFrame(cmd = InmotionI2CommandBuilder.CMD_MAIN_INFO, data = carTypeData))

        // FW 1.3
        val verData = ByteArray(25)
        verData[0] = InmotionI2CommandBuilder.MAIN_INFO_VERSION.toByte()
        verData[13] = 3
        verData[14] = 1
        codec.decode(state, buildFrame(cmd = InmotionI2CommandBuilder.CMD_MAIN_INFO, data = verData))

        val rtData = ByteArray(74)
        rtData[0] = 0xD0.toByte(); rtData[1] = 0x20
        rtData[2] = 0xF4.toByte(); rtData[3] = 0x01

        val events = codec.decode(state, buildFrame(cmd = InmotionI2CommandBuilder.CMD_REALTIME, data = rtData))
        val telem = events.filterIsInstance<DecodeEvent.TelemetryUpdate>().first().snapshot

        assertEquals(84.0f, telem.voltageV)
        assertEquals(5.0f, telem.currentA)
        // V11Y is not V11 early layout
        assertNull(telem.speedKmh)
    }
}
