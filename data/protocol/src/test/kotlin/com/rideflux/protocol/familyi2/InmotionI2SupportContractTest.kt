/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi2

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.wheel.WheelControlContract
import com.rideflux.domain.wheel.WheelIdentityEvidence
import com.rideflux.domain.wheel.WheelTelemetryCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product-level support-contract regressions for Family I2.
 *
 * The fixed-bytes V11Y oracle is independent of the parser: offsets and
 * expected values below come from the vendor-static layout in
 * `04-TELEMETRY-LAYOUTS-AND-UNITS.md`, not from `InmotionI2RealtimeV11Y`.
 */
class InmotionI2SupportContractTest {

    private fun buildFrame(cmd: Int, data: ByteArray): ByteArray {
        val body = byteArrayOf(InmotionI2Codec.FLAGS_DEFAULT.toByte(), (data.size + 1).toByte(), cmd.toByte()) + data
        return byteArrayOf(InmotionI2Codec.PREAMBLE_BYTE, InmotionI2Codec.PREAMBLE_BYTE) +
            InmotionI2Codec.escape(body) +
            byteArrayOf(InmotionI2Codec.xorChecksum8(body).toByte())
    }

    private fun mainInfo(selector: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        buildFrame(
            InmotionI2CommandBuilder.CMD_MAIN_INFO,
            byteArrayOf(selector.toByte()) + payload,
        )

    private fun version(major: Int, minor: Int, patch: Int = 0): ByteArray {
        val data = ByteArray(25)
        data[0] = InmotionI2CommandBuilder.MAIN_INFO_VERSION.toByte()
        data[11] = patch.toByte()
        data[12] = (patch shr 8).toByte()
        data[13] = minor.toByte()
        data[14] = major.toByte()
        return buildFrame(InmotionI2CommandBuilder.CMD_MAIN_INFO, data)
    }

    private fun codec() = InmotionI2WheelCodec("AA:BB:CC:DD:EE:FF")

    private fun identify(
        codec: InmotionI2WheelCodec,
        state: InmotionI2WheelCodec.InmotionI2State,
        model: String,
        major: Int?,
        minor: Int?,
    ): List<DecodeEvent> = buildList {
        addAll(codec.decode(state, mainInfo(InmotionI2CommandBuilder.MAIN_INFO_CAR_TYPE, model.toByteArray())))
        if (major != null && minor != null) addAll(codec.decode(state, version(major, minor)))
    }

    @Test
    fun `unknown model publishes family-only support with no closed controls`() {
        val codec = codec()
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State
        val events = identify(codec, state, "AUDIT-UNKNOWN-MODEL", major = null, minor = null)
        val identified = events.filterIsInstance<DecodeEvent.Identified>().last()

        assertEquals(WheelIdentityEvidence.FAMILY_ONLY, identified.support?.identityEvidence)
        assertEquals(WheelControlContract.NONE, identified.support?.controlContract)
        assertFalse(identified.capabilities.headlight)
        assertFalse(identified.capabilities.beep)
        assertFalse(identified.capabilities.maxSpeed)
        assertFalse(identified.capabilities.calibration)
        assertFalse(identified.capabilities.powerOff)
        assertTrue("encoder must stay empty for an unknown model", codec.encode(state, WheelCommand.SetHeadlight(true)).isEmpty())
        assertTrue("encoder must stay empty for an unknown model", codec.encode(state, WheelCommand.PowerOff).isEmpty())
    }

    @Test
    fun `recognised model without firmware keeps controls disabled`() {
        val codec = codec()
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State
        val identified = identify(codec, state, "V12", major = null, minor = null)
            .filterIsInstance<DecodeEvent.Identified>().last()

        assertEquals(WheelIdentityEvidence.MODEL_ONLY, identified.support?.identityEvidence)
        assertEquals(WheelControlContract.NONE, identified.support?.controlContract)
        assertFalse(identified.capabilities.headlight)
        assertTrue(codec.encode(state, WheelCommand.SetHeadlight(true)).isEmpty())
    }

    @Test
    fun `recognised model with firmware reopens the closed family command set`() {
        val codec = codec()
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State
        val identified = identify(codec, state, "V12", major = 2, minor = 0)
            .filterIsInstance<DecodeEvent.Identified>().last()

        assertEquals(WheelIdentityEvidence.MODEL_AND_FIRMWARE, identified.support?.identityEvidence)
        assertEquals(WheelControlContract.FAMILY_CLOSED, identified.support?.controlContract)
        assertTrue(identified.capabilities.headlight)
        assertTrue(codec.encode(state, WheelCommand.SetHeadlight(true)).isNotEmpty())
    }

    /**
     * Fixed 74-byte V11Y body. Only the four established fields carry data;
     * every other byte is zero.
     *
     * 0x00 = 0x0E88 = 3720 → 74.4 V   (÷50)
     * 0x02 = 0x04E2 = 1250 → 12.5 A   (÷100, signed)
     * 0x04 = 0x26AC = 9900            (charge current; must not become speed)
     * 0x06 = 0x09E2 = 2530 → 25.3 km/h (÷100)
     * 0x1c = 0x1388 = 5000 → 50 %     (÷10000 ratio)
     */
    private val v11yFixedBody: ByteArray = ByteArray(74).also {
        it[0x00] = 0x88.toByte(); it[0x01] = 0x0E
        it[0x02] = 0xE2.toByte(); it[0x03] = 0x04
        it[0x04] = 0xAC.toByte(); it[0x05] = 0x26
        it[0x06] = 0xE2.toByte(); it[0x07] = 0x09
        it[0x1c] = 0x88.toByte(); it[0x1d] = 0x13
    }

    @Test
    fun `V11Y fixed bytes publish only the established vendor offsets and scales`() {
        val codec = codec()
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State
        identify(codec, state, "V11Y", major = 1, minor = 3)

        val events = codec.decode(state, buildFrame(InmotionI2CommandBuilder.CMD_REALTIME, v11yFixedBody))
        val telem = events.filterIsInstance<DecodeEvent.TelemetryUpdate>().single().snapshot
        val identified = codec.decode(state, mainInfo(InmotionI2CommandBuilder.MAIN_INFO_SERIAL))
            .filterIsInstance<DecodeEvent.Identified>()
            .lastOrNull()

        assertEquals(74.4f, telem.voltageV!!, 0.001f)
        assertEquals(12.5f, telem.currentA!!, 0.001f)
        assertEquals(25.3f, telem.speedKmh!!, 0.001f)
        assertEquals(50.0f, telem.batteryPercent!!, 0.001f)
        // Not established by the vendor layout; null is not zero.
        assertNull(telem.tripDistanceMetres)
        assertNull(telem.mosTemperatureC)
        // The serial frame re-emits the same identity; support stays model+firmware.
        assertNotNull(identified)
        assertEquals(WheelIdentityEvidence.MODEL_AND_FIRMWARE, identified!!.support?.identityEvidence)
        assertEquals(WheelTelemetryCoverage.MODEL_LAYOUT, identified.support?.telemetryCoverage)
    }

    @Test
    fun `legacy EZCAN catalog names cannot close an I2 control contract`() {
        val codec = codec()
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State
        val identified = identify(codec, state, "V5", major = 1, minor = 3)
            .filterIsInstance<DecodeEvent.Identified>().last()

        assertEquals(WheelControlContract.NONE, identified.support?.controlContract)
        assertFalse(identified.capabilities.headlight)
        assertTrue("cross-generation catalog row must not authorise I2 writes",
            codec.encode(state, WheelCommand.SetHeadlight(true)).isEmpty())
    }

    @Test
    fun `a malformed firmware reply does not publish an inconsistent identity`() {
        val codec = codec()
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State
        identify(codec, state, "V11Y", major = 1, minor = 3)
        val events = codec.decode(
            state,
            mainInfo(
                InmotionI2CommandBuilder.MAIN_INFO_VERSION,
                "not-a-version".toByteArray(Charsets.US_ASCII),
            ),
        )
        val invalid = events.filterIsInstance<DecodeEvent.Identified>()
            .filter { it.identity.firmwareVersion == "not-a-version" }
        for (event in invalid) {
            assertEquals(WheelControlContract.NONE, event.support?.controlContract)
            assertTrue(codec.encode(state, WheelCommand.SetHeadlight(true)).isEmpty())
        }
        assertTrue("malformed firmware must be rejected or downgraded, never published as verified",
            invalid.isEmpty() || invalid.all { it.support?.controlContract == WheelControlContract.NONE })
    }

    @Test
    fun `V11Y realtime before firmware withholds the incompatible core voltage`() {
        val codec = codec()
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State
        identify(codec, state, "V11Y", major = null, minor = null)

        val events = codec.decode(
            state,
            buildFrame(InmotionI2CommandBuilder.CMD_REALTIME, v11yFixedBody),
        )
        val telemetry = events.filterIsInstance<DecodeEvent.TelemetryUpdate>().map { it.snapshot }
        assertTrue(
            "V11Y before firmware must withhold realtime or keep voltage null; got " +
                telemetry.map { it.voltageV },
            telemetry.isEmpty() || telemetry.all { it.voltageV == null },
        )
    }

    @Test
    fun `the same 74-byte frame is not decoded with V11Y offsets for another model`() {
        val codec = codec()
        val state = codec.newState() as InmotionI2WheelCodec.InmotionI2State
        identify(codec, state, "V12", major = 2, minor = 0)

        val events = codec.decode(state, buildFrame(InmotionI2CommandBuilder.CMD_REALTIME, v11yFixedBody))
        val telem = events.filterIsInstance<DecodeEvent.TelemetryUpdate>().single().snapshot

        // V12's own layout is not established, so only core voltage/current
        // are published. Reading V11Y offsets here would show a plausible but
        // fabricated 25.3 km/h and 50 % battery.
        assertNull(telem.speedKmh)
        assertNull(telem.batteryPercent)
        assertEquals(37.2f, telem.voltageV!!, 0.001f) // core parser: 3720 / 100
        assertEquals(12.5f, telem.currentA!!, 0.001f)
    }
}
