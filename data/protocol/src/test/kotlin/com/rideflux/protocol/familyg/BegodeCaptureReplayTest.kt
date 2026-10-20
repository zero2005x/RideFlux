/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyg

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.BegodeProtocolProfile
import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * End-to-end replay of the owner's Begode A2 capture (CAP-A) through the
 * profile-gated codec.
 *
 * Resources come from the same `notify_stream.bin` as `cap-a-first-cycle.hex`
 * (sha256 020ac5da...7d0739b61) and were sliced by the research tooling's
 * `make_replay_corpus.py`, which is not part of this repository:
 *  - `capA_stream.bin.gz`: the whole reassembled stream, 11,424 frames;
 *  - `capA_replay.hex`: a 400-frame clean cycle window and the 107-byte window
 *    around the wheel's ASCII reply.
 *
 * The wheel was stationary for the whole capture, so this pins framing and the
 * profile gate on real bytes, not any value under load.
 */
class BegodeCaptureReplayTest {

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("begode/$name")?.use { it.readBytes() }
            ?: error("missing test resource begode/$name")

    private fun fullStream(): ByteArray =
        GZIPInputStream(resource("capA_stream.bin.gz").inputStream()).use { it.readBytes() }

    /** The labelled windows of `capA_replay.hex`; comment lines are ignored. */
    private fun windows(): Map<String, ByteArray> {
        val sections = LinkedHashMap<String, StringBuilder>()
        var current: StringBuilder? = null
        resource("capA_replay.hex").decodeToString().lineSequence().forEach { line ->
            if (line.startsWith("#")) {
                val marker = line.removePrefix("#").trim()
                if (marker == "CYCLE" || marker == "ASCII") {
                    current = StringBuilder().also { sections[marker] = it }
                }
                return@forEach
            }
            current?.append(line.trim())
        }
        return sections.mapValues { (_, body) -> hex(body.toString()) }
    }

    private fun cycleWindow(): ByteArray = windows().getValue("CYCLE")

    private fun asciiWindow(): ByteArray = windows().getValue("ASCII")

    private class Replay(val events: List<DecodeEvent>, val bufferEmpty: Boolean) {
        val telemetryUpdates get() = events.filterIsInstance<DecodeEvent.TelemetryUpdate>()
        val snapshots get() = telemetryUpdates.map { it.snapshot }

        /** Snapshots without the wall-clock stamp, for comparing two replays. */
        val decodedFields: List<WheelTelemetry> get() = snapshots.map { it.copy(timestampMillis = 0L) }
    }

    private fun replay(
        bytes: ByteArray,
        profile: BegodeProtocolProfile = BegodeProtocolProfile.UNKNOWN,
        chunkSize: Int = 0,
    ): Replay {
        val codec = BegodeWheelCodec(deviceAddress = ADDRESS, profile = { profile })
        val state = codec.newState() as BegodeWheelCodec.BegodeState
        val events = ArrayList<DecodeEvent>()
        if (chunkSize <= 0) {
            events += codec.decode(state, bytes)
        } else {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + chunkSize, bytes.size)
                events += codec.decode(state, bytes.copyOfRange(offset, end))
                offset = end
            }
        }
        return Replay(events, state.buffer.isEmpty())
    }

    /** Decodes a window that starts on a frame boundary, 24 bytes at a time. */
    private inline fun <reified T : BegodeFrame> frames(bytes: ByteArray): List<T> {
        val out = ArrayList<T>()
        var offset = 0
        while (offset + BegodeDecoder.FRAME_SIZE <= bytes.size) {
            (BegodeDecoder.decode(bytes.copyOfRange(offset, offset + BegodeDecoder.FRAME_SIZE)) as? T)
                ?.let { out += it }
            offset += BegodeDecoder.FRAME_SIZE
        }
        return out
    }

    /**
     * 2,856 frames each of types 0x00, 0x01, 0x04 and 0x07. Three of them publish
     * telemetry and 0x01 is kept as a raw BMS frame, so every frame is accounted
     * for. The only non-frame bytes are 'N', 'V' and the 9-byte reply, which the
     * resync drops.
     */
    @Test
    fun `the full capture replays without losing a frame`() {
        val stream = fullStream()
        assertEquals(274_187, stream.size)

        BegodeProtocolProfile.entries.forEach { profile ->
            val r = replay(stream, profile)
            assertEquals("$profile", 8_568, r.telemetryUpdates.size)
            val bms = r.events.filterIsInstance<DecodeEvent.RawBmsFrame>()
            assertEquals("$profile", 2_856, bms.size)
            assertTrue(bms.all { it.frame.typeCode == 0x01 })
            assertTrue("$profile: buffer must drain", r.bufferEmpty)
            assertTrue(r.events.none { it is DecodeEvent.Malformed })
        }
    }

    /** 7 is prime, so every frame is split at a different offset. */
    @Test
    fun `chunked delivery decodes identically to a single call`() {
        val stream = fullStream()

        listOf(BegodeProtocolProfile.UNKNOWN, BegodeProtocolProfile.A2_CAPTURED).forEach { profile ->
            val single = replay(stream, profile)
            val chunked = replay(stream, profile, chunkSize = 7)
            assertEquals("$profile", single.decodedFields, chunked.decodedFields)
            assertTrue(chunked.bufferEmpty)
        }
    }

    @Test
    fun `a recognised frame identifies the wheel exactly once`() {
        assertEquals(1, replay(cycleWindow()).events.count { it is DecodeEvent.Identified })
    }

    @Test
    fun `voltage and the empty fault set keep their captured values`() {
        val r = replay(cycleWindow())

        val voltages = r.snapshots.mapNotNull { it.voltageV }.toSet()
        assertTrue("unexpected voltages: $voltages", voltages.all { it in setOf(66.72f, 66.80f, 66.88f) })
        assertTrue("the window must exercise more than one voltage", voltages.size >= 2)
        assertTrue(r.snapshots.all { it.faults?.isEmpty() != false })
    }

    /** Both Begode vendor apps read bits 13..15 as the ride mode; this A2 reports 2. */
    @Test
    fun `settings frames keep the captured ride mode`() {
        val settings = frames<BegodeFrame.SettingsAndOdometer>(cycleWindow())
        assertEquals(100, settings.size)
        assertEquals(setOf(2), settings.map { it.rideMode }.toSet())
    }

    /**
     * A two-hour countdown that drops about once per second. The window is about
     * five seconds long, so only the decrement shape is asserted.
     */
    @Test
    fun `the auto power off countdown keeps its shape`() {
        val values = frames<BegodeFrame.SettingsAndOdometer>(cycleWindow()).map { it.autoPowerOffSeconds }
        assertEquals(100, values.size)
        assertTrue("out of the captured band: $values", values.all { it in 6_600..7_200 })

        val deltas = values.zipWithNext { a, b -> b - a }
        assertTrue(
            "unexpected countdown deltas: ${deltas.filter { it !in -1..1 && it < 1_000 }}",
            deltas.all { it in -1..1 || it >= 1_000 },
        )
        assertTrue("the countdown never ticked", deltas.any { it == -1 })
    }

    /** Every live frame carries 137 at offset 14; only the opt-in legacy profile reads it as PWM. */
    @Test
    fun `offset 14 stays a mode word unless the legacy profile is chosen`() {
        val live = frames<BegodeFrame.LiveTelemetry>(cycleWindow())
        assertEquals(100, live.size)
        assertEquals(setOf(137), live.map { it.offset14Raw }.toSet())

        assertTrue(replay(cycleWindow()).snapshots.all { it.pwmPercent == null })
        val a2Pwm = replay(cycleWindow(), BegodeProtocolProfile.A2_CAPTURED).snapshots.mapNotNull { it.pwmPercent }
        assertEquals(setOf(0f), a2Pwm.toSet())

        val legacy = replay(cycleWindow(), BegodeProtocolProfile.LEGACY_DOCUMENTED)
        assertEquals(setOf(13.7f), legacy.snapshots.mapNotNull { it.pwmPercent }.toSet())
        assertTrue(legacy.snapshots.mapNotNull { it.mosTemperatureC }.isNotEmpty())
        assertTrue(legacy.snapshots.all { it.motorTemperatureC == null })
    }

    /** "Measured 0" and "unknown" are different facts. */
    @Test
    fun `captured packet 0x07 publishes measured zeros under the A2 profile`() {
        val extended = frames<BegodeFrame.ExtendedTelemetry>(cycleWindow())
        assertEquals(100, extended.size)
        assertTrue(extended.all { it.pwmOutputRaw == 0 && it.motorTempRaw == 0 && it.phaseCurrentHundredthsA == 0 })

        val r = replay(cycleWindow(), BegodeProtocolProfile.A2_CAPTURED)
        assertEquals(setOf(0f), r.snapshots.mapNotNull { it.motorTemperatureC }.toSet())
        assertEquals(setOf(0f), r.snapshots.mapNotNull { it.pwmPercent }.toSet())
    }

    @Test
    fun `the default profile keeps every disputed field absent`() {
        val r = replay(cycleWindow())

        assertTrue(r.snapshots.all { it.pwmPercent == null })
        assertTrue(r.snapshots.all { it.mosTemperatureC == null })
        assertTrue(r.snapshots.all { it.imuTemperatureC == null })
        assertTrue(r.snapshots.all { it.motorTemperatureC == null })
        assertTrue(r.snapshots.all { it.currentA == null })
        assertNotNull(r.snapshots.last().voltageV)
    }

    /** MPU6050 scaling of the captured IMU word lands at room temperature; MOS stays withdrawn. */
    @Test
    fun `the A2 profile publishes IMU temperature and never MOS`() {
        val r = replay(cycleWindow(), BegodeProtocolProfile.A2_CAPTURED)

        val imu = r.snapshots.mapNotNull { it.imuTemperatureC }
        assertTrue("the A2 profile must decode an IMU temperature", imu.isNotEmpty())
        assertTrue("IMU temperature outside the CAP-A band: ${imu.toSet()}", imu.all { it in 28f..32f })
        assertTrue(r.snapshots.all { it.mosTemperatureC == null })
        assertTrue(r.snapshots.all { it.currentA == null })
    }

    /**
     * The 107-byte window: four frames with 'N', 'V' and the reply `GW1511014`
     * between them. The text must cost no frame, however it is fragmented.
     */
    @Test
    fun `the ascii burst does not cost a neighbouring frame`() {
        val window = asciiWindow()

        val single = replay(window)
        assertEquals(3, single.telemetryUpdates.size)
        assertEquals(1, single.events.count { it is DecodeEvent.RawBmsFrame })
        assertTrue(single.bufferEmpty)
        assertTrue(single.events.none { it is DecodeEvent.Malformed })

        val chunked = replay(window, chunkSize = 5)
        assertEquals(single.decodedFields, chunked.decodedFields)
        assertTrue(chunked.bufferEmpty)
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
