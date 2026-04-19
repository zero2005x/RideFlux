/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi1

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity

/**
 * [WheelCodec] adapter for Family I1 (Inmotion legacy CAN-envelope
 * protocol: R1/R2, V3, V5, V8, V10 families).
 *
 * Handshake solicits static info and live telemetry; once static
 * info is parsed (model-dependent, not implemented here at the
 * sub-field level) the caller may refine [InmotionI1StateWord.Convention]
 * via [setStateWordConvention]. Until then, the decoder defaults to
 * the modern table (V8F and newer).
 *
 * Extended telemetry (CAN-ID `0x0F550113`) populates the running
 * snapshot. Alert records (CAN-ID `0x0F780101`) are parsed via
 * [InmotionI1AlertRecord] and mapped to [WheelAlert].
 */
class InmotionI1WheelCodec(
    private val deviceAddress: String = "",
) : WheelCodec {

    override val family: WheelFamily = WheelFamily.I1

    class InmotionI1State internal constructor() : WheelCodec.State {
        internal val buffer: ArrayList<Byte> = ArrayList(96)
        internal var last: WheelTelemetry = WheelTelemetry.EMPTY
        internal var identified: Boolean = false
        internal var convention: InmotionI1StateWord.Convention =
            InmotionI1StateWord.Convention.MODERN
        internal var speedCalibrationF: Double = 3812.0
    }

    /** Update the §4.3.1 convention and §3.5.2 speed constant for the session. */
    fun setStateWordConvention(
        state: WheelCodec.State,
        convention: InmotionI1StateWord.Convention,
        speedCalibrationF: Double = 3812.0,
    ) {
        val s = state as InmotionI1State
        s.convention = convention
        s.speedCalibrationF = speedCalibrationF
    }

    override fun newState(): WheelCodec.State = InmotionI1State()

    override fun handshakeFrames(state: WheelCodec.State): List<ByteArray> = listOf(
        InmotionI1CommandBuilder.requestStaticInfo(),
        InmotionI1CommandBuilder.requestLiveTelemetry(),
    )

    override fun decode(state: WheelCodec.State, bytes: ByteArray): List<DecodeEvent> {
        val s = state as InmotionI1State
        for (b in bytes) s.buffer.add(b)

        val events = ArrayList<DecodeEvent>()
        var progress = true
        while (progress && s.buffer.size >= 21) {
            progress = false
            val wire = ByteArray(s.buffer.size) { s.buffer[it] }
            when (val r = InmotionI1Decoder.decode(wire, offset = 0)) {
                is InmotionI1DecodeResult.Ok -> {
                    repeat(r.consumedBytes) { s.buffer.removeAt(0) }
                    progress = true

                    if (!s.identified) {
                        s.identified = true
                        events.add(
                            DecodeEvent.Identified(
                                identity = WheelIdentity(
                                    address = deviceAddress,
                                    family = WheelFamily.I1,
                                    modelName = "Inmotion I1",
                                ),
                                capabilities = DEFAULT_CAPABILITIES,
                            ),
                        )
                    }

                    val now = System.currentTimeMillis()
                    val frame = r.frame
                    when (frame.canId) {
                        InmotionI1CommandBuilder.CAN_ID_LIVE_TELEMETRY -> {
                            if (frame.isExtended &&
                                frame.exData.size >= InmotionI1ExtendedTelemetry.MIN_EX_DATA_SIZE
                            ) {
                                val t = InmotionI1ExtendedTelemetry.parse(frame.exData)
                                val stateDecoded = InmotionI1StateWord.decode(t.stateWordRaw, s.convention)
                                val merged = s.last.copy(
                                    timestampMillis = now,
                                    voltageV = t.voltageV.toFloat(),
                                    phaseCurrentA = t.phaseCurrentA.toFloat(),
                                    speedKmh = t.speedKmh(s.speedCalibrationF).toFloat(),
                                    tripDistanceMetres = t.tripDistanceMetres.toInt(),
                                    pitchAngleDegrees = (t.pitchRaw.toDouble() / 65536.0).toFloat(),
                                    rollAngleDegrees = (t.rollRaw.toDouble() / 90.0).toFloat(),
                                    mosTemperatureC = t.temperature1Celsius.toFloat(),
                                    motorTemperatureC = t.temperature2Celsius.toFloat(),
                                    workMode = stateDecoded.displayString,
                                )
                                s.last = merged
                                events.add(DecodeEvent.TelemetryUpdate(merged))
                            }
                        }
                        InmotionI1CommandBuilder.CAN_ID_ALERT -> {
                            val record = InmotionI1AlertRecord.parse(frame.data8)
                            events.add(DecodeEvent.Alert(mapAlert(record, now)))
                        }
                    }
                }
                is InmotionI1DecodeResult.Fail -> {
                    when (r.error) {
                        is InmotionI1DecodeError.TooShort -> {
                            // Wait for more bytes.
                        }
                        else -> {
                            s.buffer.removeAt(0)
                            events.add(
                                DecodeEvent.Malformed(
                                    reason = "I1: ${r.error}",
                                    offendingBytes = null,
                                ),
                            )
                            progress = true
                        }
                    }
                }
            }
        }
        return events
    }

    override fun encode(state: WheelCodec.State, command: WheelCommand): List<ByteArray> =
        when (command) {
            is WheelCommand.Beep -> listOf(InmotionI1CommandBuilder.beep())
            is WheelCommand.SetHeadlight -> listOf(InmotionI1CommandBuilder.setHeadlight(command.on))
            is WheelCommand.SetLedStrip -> listOf(
                if (command.on) InmotionI1CommandBuilder.ledStripOn()
                else InmotionI1CommandBuilder.ledStripOff(),
            )
            is WheelCommand.PowerOff -> listOf(InmotionI1CommandBuilder.powerOff())
            is WheelCommand.Calibrate -> listOf(InmotionI1CommandBuilder.calibration())
            is WheelCommand.SetMaxSpeedKmh -> listOf(InmotionI1CommandBuilder.setMaxSpeed(command.kmh.toDouble()))
            is WheelCommand.SetPedalSensitivity -> listOf(InmotionI1CommandBuilder.setPedalSensitivity(command.level))
            is WheelCommand.SetPedalHorizontal -> listOf(
                InmotionI1CommandBuilder.setHorizontalTilt(command.angleDegrees.toDouble()),
            )
            is WheelCommand.SetRideMode -> listOf(
                InmotionI1CommandBuilder.setRideModeClassic(command.modeCode != 0),
            )
            is WheelCommand.SetVolume -> listOf(InmotionI1CommandBuilder.setVolume(command.percent))
            is WheelCommand.PlaySound -> listOf(InmotionI1CommandBuilder.playSound(command.soundId))
            is WheelCommand.UnlockWithPin -> {
                if (command.pin.length == 6) listOf(InmotionI1CommandBuilder.sendPin(command.pin))
                else emptyList()
            }
            is WheelCommand.Raw -> listOf(command.bytes)
            else -> emptyList()
        }

    private fun mapAlert(r: InmotionI1AlertRecord, now: Long): WheelAlert =
        when (val e = r.event) {
            is InmotionI1AlertRecord.Event.TiltBack -> WheelAlert.TiltBack(
                timestampMillis = now,
                speedKmh = e.speedKmh.toFloat(),
                limit = e.limit.toFloat(),
            )
            is InmotionI1AlertRecord.Event.SpeedCutoff -> WheelAlert.SpeedCutoff(
                timestampMillis = now,
                speedKmh = e.speedKmh.toFloat(),
            )
            is InmotionI1AlertRecord.Event.LowBattery -> WheelAlert.LowBattery(
                timestampMillis = now,
                voltageV = e.voltageV.toFloat(),
            )
            is InmotionI1AlertRecord.Event.FallDownDetected -> WheelAlert.FallDown(now)
            else -> WheelAlert.Raw(
                timestampMillis = now,
                domain = "I1",
                code = r.alertId,
                payload = r.rawData8,
            )
        }

    companion object {
        val DEFAULT_CAPABILITIES: WheelCapabilities = WheelCapabilities(
            headlight = true,
            horn = false,
            beep = true,
            ledStrip = true,
            decorativeLights = false,
            rideModes = true,
            maxSpeed = true,
            tiltback = false,
            pedalSensitivity = true,
            pedalHorizontal = true,
            calibration = true,
            powerOff = true,
            volume = true,
            playSound = true,
            pinUnlock = true,
            asyncAlerts = true,
        )
    }
}
