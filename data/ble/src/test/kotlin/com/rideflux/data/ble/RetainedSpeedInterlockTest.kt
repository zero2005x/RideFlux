/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.command.WheelCommand
import com.rideflux.protocol.familyg.BegodeWheelCodec
import com.rideflux.protocol.familyi2.InmotionI2CommandBuilder
import com.rideflux.protocol.familyi2.InmotionI2WheelCodec
import com.rideflux.protocol.familyk.KingSongWheelCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A critical command needs three fresh zero-speed samples. Only a frame that
 * carries a decoded speed is a sample: a frame without one (Begode 0x04/0x07,
 * KingSong page B, a short InMotion I2 realtime record) must neither add to
 * the count nor keep an old confirmation fresh.
 *
 * Each family has a control case that earns the confirmation from real speed
 * frames, so a failure in the other cases cannot come from a bad fixture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetainedSpeedInterlockTest {

    private var now = 0L

    private inner class Rig(codec: WheelCodec, scope: TestScope) {
        val transport = FakeBleTransport()
        val connection = WheelConnectionImpl(transport, codec, scope.backgroundScope, clock = { now })
        private val testScope = scope

        suspend fun start() {
            connection.start()
            testScope.runCurrent()
        }

        suspend fun feed(frame: ByteArray, atMillis: Long) {
            now = atMillis
            transport.emit(frame)
            testScope.runCurrent()
        }

        suspend fun assertBlocked(command: WheelCommand, atMillis: Long) {
            now = atMillis
            val before = transport.writes.size
            val outcome = connection.dispatch(command)
            assertTrue(
                "expected the interlock to refuse $command, got $outcome",
                outcome is CommandOutcome.InvalidArgument,
            )
            assertEquals("no bytes may reach the wheel", before, transport.writes.size)
        }

        suspend fun assertAllowed(command: WheelCommand, atMillis: Long) {
            now = atMillis
            val before = transport.writes.size
            val outcome = connection.dispatch(command)
            assertEquals(CommandOutcome.Success, outcome)
            assertTrue("the command must be written", transport.writes.size > before)
        }
    }

    /** The fixture really is a frame the codec turns into a telemetry update. */
    private fun assertPublishes(codec: WheelCodec, vararg frames: ByteArray) {
        val state = codec.newState()
        val last = frames.map { codec.decode(state, it) }.last()
        assertTrue(
            "fixture must produce a TelemetryUpdate, got $last",
            last.any { it is DecodeEvent.TelemetryUpdate },
        )
    }

    // ---- Begode ----------------------------------------------------------

    private fun begode() = BegodeWheelCodec("AA:BB:CC:DD:EE:FF")

    /** WheelLog sample frame "A": a parked wheel, speed 0 (see NOTICE). */
    private val begodeParkedLive = hex("55AA19F0000000000000012CFDCA0001FFF800185A5A5A5A")

    /** Type 0x04 settings/odometer: no speed field. */
    private val begodeSettings = hex("55AA" + "00".repeat(16) + "0418" + "5A5A5A5A")

    /** Type 0x07 extended telemetry: no speed field. */
    private val begodeExtended = hex("55AA" + "00".repeat(16) + "0718" + "5A5A5A5A")

    @Test fun `begode control - three parked live frames allow calibration`() = runTest {
        val rig = Rig(begode(), this)
        rig.start()
        rig.feed(begodeParkedLive, 0)
        rig.feed(begodeParkedLive, 100)
        rig.feed(begodeParkedLive, 200)
        rig.assertAllowed(WheelCommand.Calibrate, 300)
    }

    @Test fun `begode settings frames do not count as stationary samples`() = runTest {
        assertPublishes(begode(), begodeParkedLive, begodeSettings)
        val rig = Rig(begode(), this)
        rig.start()
        rig.feed(begodeParkedLive, 0)
        rig.feed(begodeSettings, 100)
        rig.feed(begodeSettings, 200)
        rig.assertBlocked(WheelCommand.Calibrate, 300)
    }

    @Test fun `begode extended frames do not count as stationary samples`() = runTest {
        assertPublishes(begode(), begodeParkedLive, begodeExtended)
        val rig = Rig(begode(), this)
        rig.start()
        rig.feed(begodeParkedLive, 0)
        rig.feed(begodeExtended, 100)
        rig.feed(begodeExtended, 200)
        rig.assertBlocked(WheelCommand.Calibrate, 300)
    }

    @Test fun `begode settings frames cannot keep a confirmation fresh after live frames stop`() = runTest {
        val rig = Rig(begode(), this)
        rig.start()
        rig.feed(begodeParkedLive, 0)
        rig.feed(begodeParkedLive, 100)
        rig.feed(begodeParkedLive, 200)
        // Live frames stop; only settings frames keep arriving.
        rig.feed(begodeSettings, 1_000)
        rig.feed(begodeSettings, 2_000)
        rig.feed(begodeSettings, 3_000)
        // The last real speed sample is 2.9 s old, past the 1.5 s window.
        rig.assertBlocked(WheelCommand.Calibrate, 3_100)
    }

    // ---- KingSong --------------------------------------------------------

    private fun kingSong() = KingSongWheelCodec(deviceAddress = "AA:BB:CC:DD:EE:FF")

    private fun kingSongPageA(speedHundredths: Int): ByteArray = ByteArray(20).also {
        it[0] = 0xAA.toByte(); it[1] = 0x55
        it[2] = 0xD0.toByte(); it[3] = 0x20 // 84.00 V
        it[4] = (speedHundredths and 0xFF).toByte()
        it[5] = ((speedHundredths ushr 8) and 0xFF).toByte()
        it[12] = 0xAC.toByte(); it[13] = 0x0D // 35.00 °C
        it[14] = 1; it[15] = 0xE0.toByte()
        it[16] = 0xA9.toByte()
        it[18] = 0x5A; it[19] = 0x5A
    }

    /** Page B carries trip, charging and board temperature, not speed. */
    private val kingSongPageB: ByteArray = ByteArray(20).also {
        it[0] = 0xAA.toByte(); it[1] = 0x55
        it[2] = 0xF4.toByte(); it[3] = 0x01 // trip 500 m
        it[14] = 0xA0.toByte(); it[15] = 0x0F // 40.00 °C
        it[16] = 0xB9.toByte()
        it[18] = 0x5A; it[19] = 0x5A
    }

    @Test fun `kingsong control - three stationary page A frames allow power off`() = runTest {
        val rig = Rig(kingSong(), this)
        rig.start()
        rig.feed(kingSongPageA(0), 0)
        rig.feed(kingSongPageA(0), 100)
        rig.feed(kingSongPageA(0), 200)
        rig.assertAllowed(WheelCommand.PowerOff, 300)
    }

    @Test fun `kingsong page B frames do not count as stationary samples`() = runTest {
        assertPublishes(kingSong(), kingSongPageA(0), kingSongPageB)
        val rig = Rig(kingSong(), this)
        rig.start()
        rig.feed(kingSongPageA(0), 0)
        rig.feed(kingSongPageB, 100)
        rig.feed(kingSongPageB, 200)
        rig.assertBlocked(WheelCommand.PowerOff, 300)
    }

    // ---- InMotion I2, V11 early layout ------------------------------------

    private fun inmotion() = InmotionI2WheelCodec("AA:BB:CC:DD:EE:FF")

    private fun i2(cmd: Int, data: ByteArray) = InmotionI2CommandBuilder.build(0x14, cmd, data)

    private val i2CarTypeV11 = i2(
        InmotionI2CommandBuilder.CMD_MAIN_INFO,
        byteArrayOf(InmotionI2CommandBuilder.MAIN_INFO_CAR_TYPE.toByte()) + "V11".toByteArray(Charsets.US_ASCII),
    )

    /** Firmware 1.3.50: below 1.4, so the early layout (speed at offset 4) applies. */
    private val i2Version13 = i2(
        InmotionI2CommandBuilder.CMD_MAIN_INFO,
        ByteArray(25).also {
            it[0] = InmotionI2CommandBuilder.MAIN_INFO_VERSION.toByte()
            it[11] = 50; it[13] = 3; it[14] = 1
        },
    )

    /** Full early-layout record at 84.00 V and speed 0. */
    private val i2RealtimeStationary = i2(
        InmotionI2CommandBuilder.CMD_REALTIME,
        ByteArray(56).also { it[0] = 0xD0.toByte(); it[1] = 0x20 },
    )

    /** Long enough for the voltage/current core, too short for the speed offset. */
    private val i2RealtimeShort = i2(
        InmotionI2CommandBuilder.CMD_REALTIME,
        ByteArray(8).also { it[0] = 0xD0.toByte(); it[1] = 0x20 },
    )

    private suspend fun Rig.identifyV11Early() {
        feed(i2CarTypeV11, 0)
        feed(i2Version13, 0)
    }

    @Test fun `inmotion control - three stationary realtime records allow a pedal change`() = runTest {
        val rig = Rig(inmotion(), this)
        rig.start()
        rig.identifyV11Early()
        rig.feed(i2RealtimeStationary, 0)
        rig.feed(i2RealtimeStationary, 100)
        rig.feed(i2RealtimeStationary, 200)
        rig.assertAllowed(WheelCommand.SetPedalSensitivity(50), 300)
    }

    @Test fun `inmotion short realtime records do not count as stationary samples`() = runTest {
        assertPublishes(inmotion(), i2CarTypeV11, i2Version13, i2RealtimeStationary, i2RealtimeShort)
        val rig = Rig(inmotion(), this)
        rig.start()
        rig.identifyV11Early()
        rig.feed(i2RealtimeStationary, 0)
        rig.feed(i2RealtimeShort, 100)
        rig.feed(i2RealtimeShort, 200)
        rig.assertBlocked(WheelCommand.SetPedalSensitivity(50), 300)
    }

    // ---- InMotion I2, V11Y --------------------------------------------------

    private val i2CarTypeV11Y = i2(
        InmotionI2CommandBuilder.CMD_MAIN_INFO,
        byteArrayOf(InmotionI2CommandBuilder.MAIN_INFO_CAR_TYPE.toByte()) + "V11Y".toByteArray(Charsets.US_ASCII),
    )

    /**
     * A V11Y realtime record at 84.00 V with the given s16LE words at the
     * vendor speed offset (0x08) and the charge-current offset (0x06). Bytes
     * are placed by hand, not through the parser's constants.
     */
    private fun v11yRealtime(speedHundredths: Int, chargeCurrentHundredths: Int, size: Int = 74) = i2(
        InmotionI2CommandBuilder.CMD_REALTIME,
        ByteArray(size).also {
            it[0x00] = 0xD0.toByte(); it[0x01] = 0x20
            it[0x06] = (chargeCurrentHundredths and 0xFF).toByte()
            it[0x07] = ((chargeCurrentHundredths ushr 8) and 0xFF).toByte()
            it[0x08] = (speedHundredths and 0xFF).toByte()
            it[0x09] = ((speedHundredths ushr 8) and 0xFF).toByte()
        },
    )

    private suspend fun Rig.identifyV11Y() {
        feed(i2CarTypeV11Y, 0)
        feed(i2Version13, 0)
    }

    /**
     * V11Y is read-only (no closed write contract), so no command can succeed.
     * The interlock runs before the capability gate: [CommandOutcome.Unsupported]
     * therefore means the interlock accepted three fresh stationary samples,
     * and [CommandOutcome.InvalidArgument] means it refused.
     */
    @Test fun `v11y control - three stationary records satisfy the interlock while charging`() = runTest {
        // A non-zero charge current at 0x06 must not be read as speed.
        val parked = v11yRealtime(speedHundredths = 0, chargeCurrentHundredths = 1_500)
        assertPublishes(inmotion(), i2CarTypeV11Y, i2Version13, parked)
        val rig = Rig(inmotion(), this)
        rig.start()
        rig.identifyV11Y()
        rig.feed(parked, 0)
        rig.feed(parked, 100)
        rig.feed(parked, 200)
        now = 300
        val before = rig.transport.writes.size
        val outcome = rig.connection.dispatch(WheelCommand.SetPedalSensitivity(50))
        assertEquals(CommandOutcome.Unsupported(WheelCommand.SetPedalSensitivity(50)), outcome)
        assertEquals("a read-only model writes nothing", before, rig.transport.writes.size)
    }

    @Test fun `v11y speed at the vendor offset keeps critical commands blocked`() = runTest {
        // 25.30 km/h at 0x08; the charge-current word at 0x06 reads zero.
        val moving = v11yRealtime(speedHundredths = 2_530, chargeCurrentHundredths = 0)
        val rig = Rig(inmotion(), this)
        rig.start()
        rig.identifyV11Y()
        rig.feed(moving, 0)
        rig.feed(moving, 100)
        rig.feed(moving, 200)
        rig.assertBlocked(WheelCommand.SetPedalSensitivity(50), 300)
    }

    @Test fun `v11y short records do not count as stationary samples`() = runTest {
        val parked = v11yRealtime(speedHundredths = 0, chargeCurrentHundredths = 0)
        val short = v11yRealtime(speedHundredths = 0, chargeCurrentHundredths = 0, size = 73)
        assertPublishes(inmotion(), i2CarTypeV11Y, i2Version13, parked, short)
        val rig = Rig(inmotion(), this)
        rig.start()
        rig.identifyV11Y()
        rig.feed(parked, 0)
        rig.feed(short, 100)
        rig.feed(short, 200)
        rig.assertBlocked(WheelCommand.SetPedalSensitivity(50), 300)
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
