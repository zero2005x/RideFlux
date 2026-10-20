/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyg

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.telemetry.TelemetryField
import com.rideflux.domain.wheel.BegodeProtocolProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile gates which words may be published. The documented live vector and a
 * synthetic packet 0x07 are enough; no rider backup is imported.
 */
class BegodeProfileCodecTest {

    @Test
    fun unknownDoesNotPublishDisputedPwmOrTemperatures() {
        val update = decode(BegodeProtocolProfile.UNKNOWN, LIVE_VECTOR).singleTelemetry()
        assertNull(update.snapshot.pwmPercent)
        assertNull(update.snapshot.mosTemperatureC)
        assertNull(update.snapshot.imuTemperatureC)
        assertNull(update.snapshot.motorTemperatureC)
        assertNull(update.snapshot.currentA)
        assertEquals(3f, update.snapshot.phaseCurrentA)
        assertTrue(TelemetryField.PWM in update.invalidated)
        assertTrue(TelemetryField.CURRENT in update.invalidated)
    }

    @Test
    fun a2ModeWord137IsNotPwmAndImuUsesMpu6050() {
        val update = decode(BegodeProtocolProfile.A2_CAPTURED, liveWithOffset14(137)).singleTelemetry()
        assertNull(update.snapshot.pwmPercent)
        assertNull(update.snapshot.mosTemperatureC)
        org.junit.Assert.assertEquals(34.865f, update.snapshot.imuTemperatureC!!, 0.01f)
        assertNull(update.snapshot.currentA)
        assertTrue(TelemetryField.MOS_TEMPERATURE in update.invalidated)
        assertTrue(TelemetryField.PWM in update.invalidated)
    }

    @Test
    fun inRangePacket7PwmSurvivesTheNextLiveFrame() {
        val codec = BegodeWheelCodec(deviceAddress = ADDRESS, profile = { BegodeProtocolProfile.A2_CAPTURED })
        val state = codec.newState()
        codec.decode(state, liveWithOffset14(137))
        val extended = codec.decode(state, extended(pwm = 42, motorC = 55)).singleTelemetry()
        assertEquals(42f, extended.snapshot.pwmPercent)
        assertEquals(55f, extended.snapshot.motorTemperatureC)

        val live = codec.decode(state, LIVE_VECTOR).singleTelemetry()
        assertEquals(42f, live.snapshot.pwmPercent)
        assertEquals(55f, live.snapshot.motorTemperatureC)
        assertNull(live.snapshot.mosTemperatureC)
        org.junit.Assert.assertEquals(34.865f, live.snapshot.imuTemperatureC!!, 0.01f)
    }

    @Test
    fun outOfRangePacket7PwmWithdrawsThePreviousReading() {
        val codec = BegodeWheelCodec(deviceAddress = ADDRESS, profile = { BegodeProtocolProfile.A2_CAPTURED })
        val state = codec.newState()
        codec.decode(state, extended(pwm = 42, motorC = 55))
        val withdrawn = codec.decode(state, extended(pwm = 150, motorC = 55)).singleTelemetry()
        assertNull(withdrawn.snapshot.pwmPercent)
        assertTrue(TelemetryField.PWM in withdrawn.invalidated)
        assertEquals(55f, withdrawn.snapshot.motorTemperatureC)
    }

    @Test
    fun legacyKeepsDocumentedPwmAndMpu6500Temperatures() {
        val update = decode(BegodeProtocolProfile.LEGACY_DOCUMENTED, LIVE_VECTOR).singleTelemetry()
        assertEquals(0.1f, update.snapshot.pwmPercent)
        org.junit.Assert.assertEquals(19.305f, update.snapshot.mosTemperatureC!!, 0.01f)
        assertEquals(update.snapshot.mosTemperatureC, update.snapshot.imuTemperatureC)
        assertNull(update.snapshot.motorTemperatureC)
        assertTrue(TelemetryField.MOTOR_TEMPERATURE in update.invalidated)
    }

    @Test
    fun profileChangeAppliesOnTheNextFrameAndClearsStaleFields() {
        var profile = BegodeProtocolProfile.A2_CAPTURED
        val codec = BegodeWheelCodec(deviceAddress = ADDRESS, profile = { profile })
        val state = codec.newState()
        codec.decode(state, extended(pwm = 42, motorC = 55))
        profile = BegodeProtocolProfile.UNKNOWN
        val cleared = codec.decode(state, LIVE_VECTOR).singleTelemetry()
        assertNull(cleared.snapshot.pwmPercent)
        assertNull(cleared.snapshot.motorTemperatureC)
        assertNull(cleared.snapshot.imuTemperatureC)
        assertNull(cleared.snapshot.mosTemperatureC)
    }

    @Test
    fun nonA2Packet7DoesNotPublishPwm() {
        val update = decode(BegodeProtocolProfile.UNKNOWN, extended(pwm = 42, motorC = 55)).singleTelemetry()
        assertNull(update.snapshot.pwmPercent)
        assertNull(update.snapshot.motorTemperatureC)
    }

    private fun decode(profile: BegodeProtocolProfile, frame: ByteArray): List<DecodeEvent> {
        val codec = BegodeWheelCodec(deviceAddress = ADDRESS, profile = { profile })
        return codec.decode(codec.newState(), frame)
    }

    private fun List<DecodeEvent>.singleTelemetry(): DecodeEvent.TelemetryUpdate =
        filterIsInstance<DecodeEvent.TelemetryUpdate>().single()

    private fun liveWithOffset14(raw: Int): ByteArray {
        val frame = LIVE_VECTOR.copyOf()
        frame[14] = (raw ushr 8).toByte()
        frame[15] = raw.toByte()
        return frame
    }

    private fun extended(pwm: Int, motorC: Int): ByteArray = ByteArray(24).also { frame ->
        frame[0] = 0x55
        frame[1] = 0xAA.toByte()
        frame[6] = (motorC ushr 8).toByte()
        frame[7] = motorC.toByte()
        frame[8] = (pwm ushr 8).toByte()
        frame[9] = pwm.toByte()
        frame[18] = 0x07
        for (i in 20..23) frame[i] = 0x5A
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
        val LIVE_VECTOR = byteArrayOf(
            0x55, 0xAA.toByte(), 0x19, 0xF0.toByte(), 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x01, 0x2C, 0xFD.toByte(), 0xCA.toByte(), 0x00, 0x01,
            0xFF.toByte(), 0xF8.toByte(), 0x00, 0x18, 0x5A, 0x5A, 0x5A, 0x5A,
        )
    }
}
