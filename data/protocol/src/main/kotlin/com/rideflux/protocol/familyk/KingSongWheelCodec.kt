/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyk

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.telemetry.ChargingState
import com.rideflux.domain.telemetry.RideMode
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity

/**
 * [WheelCodec] adapter for Family K (KingSong).
 *
 * Merges live-page A (`0xA9`) and live-page B (`0xB9`) frames into a
 * single rolling [WheelTelemetry] snapshot.
 */
class KingSongWheelCodec(
    private val deviceAddress: String = "",
) : WheelCodec {

    override val family: WheelFamily = WheelFamily.K

    class KingSongState internal constructor() : WheelCodec.State {
        internal val buffer: ArrayList<Byte> = ArrayList(40)
        internal var last: WheelTelemetry = WheelTelemetry.EMPTY
        internal var identified: Boolean = false
    }

    override fun newState(): WheelCodec.State = KingSongState()

    override fun handshakeFrames(state: WheelCodec.State): List<ByteArray> = listOf(
        KingSongCommandBuilder.requestSerialNumber(),
        KingSongCommandBuilder.requestDeviceName(),
    )

    override fun decode(state: WheelCodec.State, bytes: ByteArray): List<DecodeEvent> {
        val s = state as KingSongState
        for (b in bytes) s.buffer.add(b)

        val events = ArrayList<DecodeEvent>()
        while (s.buffer.size >= 20) {
            val frameBytes = ByteArray(20) { s.buffer[it] }
            val frame = KingSongDecoder.decode(frameBytes)
            if (frame == null) {
                s.buffer.removeAt(0)
                continue
            }
            repeat(20) { s.buffer.removeAt(0) }

            val now = System.currentTimeMillis()
            when (frame) {
                is KingSongFrame.LivePageA -> {
                    // Only declare the wheel identified after a real live
                    // telemetry frame has been decoded (the handshake name/
                    // serial replies are surfaced as Unknown frames and must
                    // not be taken as proof of identity).
                    if (!s.identified) {
                        s.identified = true
                        events.add(
                            DecodeEvent.Identified(
                                identity = WheelIdentity(
                                    address = deviceAddress,
                                    family = WheelFamily.K,
                                    modelName = "KingSong",
                                ),
                                capabilities = DEFAULT_CAPABILITIES,
                            ),
                        )
                    }
                    val merged = s.last.copy(
                        timestampMillis = now,
                        voltageV = frame.voltageVolts.toFloat(),
                        speedKmh = frame.speedKmh.toFloat(),
                        totalDistanceMetres = frame.totalDistanceMeters,
                        currentA = frame.currentAmps.toFloat(),
                        mosTemperatureC = frame.temperatureCelsius.toFloat(),
                        rideMode = if (frame.modeMarkerPresent) {
                            RideMode(frame.modeEnum, "Mode ${frame.modeEnum}")
                        } else {
                            s.last.rideMode
                        },
                    )
                    s.last = merged
                    events.add(DecodeEvent.TelemetryUpdate(merged))
                }
                is KingSongFrame.LivePageB -> {
                    val merged = s.last.copy(
                        timestampMillis = now,
                        tripDistanceMetres = frame.tripDistanceMeters.toInt(),
                        boardTemperatureC = frame.temperatureCelsius.toFloat(),
                        chargingState = if (frame.charging) ChargingState.CHARGING
                            else ChargingState.NOT_CONNECTED,
                    )
                    s.last = merged
                    events.add(DecodeEvent.TelemetryUpdate(merged))
                }
                is KingSongFrame.Unknown -> Unit
            }
        }
        return events
    }

    override fun encode(state: WheelCodec.State, command: WheelCommand): List<ByteArray> =
        when (command) {
            is WheelCommand.Beep -> listOf(KingSongCommandBuilder.beep())
            is WheelCommand.Calibrate -> listOf(KingSongCommandBuilder.wheelCalibration())
            is WheelCommand.PowerOff -> listOf(KingSongCommandBuilder.powerOff())
            is WheelCommand.SetHeadlight -> listOf(
                KingSongCommandBuilder.setLightMode(
                    if (command.on) KingSongCommandBuilder.LightMode.ON
                    else KingSongCommandBuilder.LightMode.OFF,
                ),
            )
            is WheelCommand.SetMaxSpeedKmh -> {
                // NOTE: the single 0x85 frame carries the three alarm
                // thresholds alongside max speed; writing 0 to the alarms
                // disables them. The protocol provides no way to change
                // only the max speed, so this is an accepted limitation
                // (forceWrite=true keeps an all-zero payload a genuine
                // write rather than being rewritten into a 0x98 query).
                listOf(
                    KingSongCommandBuilder.setAlarmAndMaxSpeed(
                        alarm1Kmh = 0,
                        alarm2Kmh = 0,
                        alarm3Kmh = 0,
                        maxSpeedKmh = command.kmh.toInt().coerceIn(0, 255),
                        forceWrite = true,
                    ),
                )
            }
            is WheelCommand.SetRideMode -> emptyList()
            is WheelCommand.Raw -> listOf(command.bytes)
            else -> emptyList()
        }

    companion object {
        // Capabilities mirror what encode() actually implements.
        // ledStrip / decorativeLights / rideModes / pedalSensitivity
        // have no encode() mapping, so they are advertised as
        // unsupported rather than exposing controls that always fail.
        val DEFAULT_CAPABILITIES: WheelCapabilities = WheelCapabilities(
            headlight = true,
            horn = false,
            beep = true,
            ledStrip = false,
            decorativeLights = false,
            rideModes = false,
            maxSpeed = true,
            tiltback = false,
            pedalSensitivity = false,
            pedalHorizontal = false,
            calibration = true,
            powerOff = true,
            volume = false,
            playSound = false,
            pinUnlock = false,
            asyncAlerts = false,
        )
    }
}
