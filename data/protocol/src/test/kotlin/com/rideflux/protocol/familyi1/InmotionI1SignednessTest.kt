/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi1

import com.rideflux.domain.codec.DecodeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pitch, roll and the two speed components of the live-telemetry record are signed 32-bit
 * values. Read as unsigned, a slightly negative attitude becomes tens of millions of degrees
 * and a standing wheel becomes millions of km/h.
 *
 * The frames are real ones from a V5F on a bench (see [InmotionI1RealFrames]): both speed
 * components are bit-identical in every captured frame, both attitude angles change sign
 * within the capture, and the magnitudes are the small numbers a level wheel gives.
 */
class InmotionI1SignednessTest {

    private fun telemetryOf(wire: ByteArray): InmotionI1ExtendedTelemetry {
        val result = InmotionI1Decoder.decode(wire)
        assertTrue("frame must decode: $result", result is InmotionI1DecodeResult.Ok)
        val frame = (result as InmotionI1DecodeResult.Ok).frame
        assertEquals(0x0F550113L, frame.canId)
        return InmotionI1ExtendedTelemetry.parse(frame.exData)
    }

    @Test
    fun `pitch is signed`() {
        assertEquals(-3988L, telemetryOf(InmotionI1RealFrames.negativeAngles).pitchRaw)
        assertEquals(-1312L, telemetryOf(InmotionI1RealFrames.positiveRoll).pitchRaw)
        assertEquals(1497L, telemetryOf(InmotionI1RealFrames.positivePitch).pitchRaw)
    }

    @Test
    fun `roll is signed`() {
        assertEquals(-74L, telemetryOf(InmotionI1RealFrames.negativeAngles).rollRaw)
        assertEquals(50L, telemetryOf(InmotionI1RealFrames.positiveRoll).rollRaw)
        assertEquals(-579L, telemetryOf(InmotionI1RealFrames.positivePitch).rollRaw)
    }

    @Test
    fun `both speed components are signed`() {
        val negative = telemetryOf(InmotionI1RealFrames.negativeAngles)
        assertEquals(-1247L, negative.speedARaw)
        assertEquals(-1247L, negative.speedBRaw)

        val positive = telemetryOf(InmotionI1RealFrames.positiveRoll)
        assertEquals(1637L, positive.speedARaw)
        assertEquals(1637L, positive.speedBRaw)

        val otherNegative = telemetryOf(InmotionI1RealFrames.positivePitch)
        assertEquals(-857L, otherNegative.speedARaw)
        assertEquals(-857L, otherNegative.speedBRaw)
    }

    @Test
    fun `the derived speed is a rideable number for either sign`() {
        assertEquals(1.178, telemetryOf(InmotionI1RealFrames.negativeAngles).speedKmh(), 1e-3)
        assertEquals(1.546, telemetryOf(InmotionI1RealFrames.positiveRoll).speedKmh(), 1e-3)
        assertEquals(0.809, telemetryOf(InmotionI1RealFrames.positivePitch).speedKmh(), 1e-3)
    }

    @Test
    fun `the codec publishes plausible attitude and speed for those frames`() {
        val codec = InmotionI1WheelCodec("AA:BB:CC:DD:EE:FF")
        val state = codec.newState()

        val snapshots = InmotionI1RealFrames.all
            .flatMap { codec.decode(state, it) }
            .filterIsInstance<DecodeEvent.TelemetryUpdate>()
            .map { it.snapshot }

        assertEquals(InmotionI1RealFrames.all.size, snapshots.size)
        val level = snapshots[2] // negativeAngles
        assertEquals(-0.0609f, level.pitchAngleDegrees!!, 1e-3f)
        assertEquals(-0.8222f, level.rollAngleDegrees!!, 1e-3f)
        assertEquals(1.178f, level.speedKmh!!, 1e-3f)
        for (snapshot in snapshots) {
            assertTrue("pitch ${snapshot.pitchAngleDegrees}", snapshot.pitchAngleDegrees!! in -45f..45f)
            assertTrue("roll ${snapshot.rollAngleDegrees}", snapshot.rollAngleDegrees!! in -45f..45f)
            assertTrue("speed ${snapshot.speedKmh}", snapshot.speedKmh!! in 0f..100f)
        }
    }
}
