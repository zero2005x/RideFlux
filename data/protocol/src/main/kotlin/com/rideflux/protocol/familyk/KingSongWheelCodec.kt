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

            val now = System.currentTimeMillis()
            when (frame) {
                is KingSongFrame.LivePageA -> {
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
            is WheelCommand.SetMaxSpeedKmh -> listOf(
                KingSongCommandBuilder.setAlarmAndMaxSpeed(
                    alarm1Kmh = 0,
                    alarm2Kmh = 0,
                    alarm3Kmh = 0,
                    maxSpeedKmh = command.kmh.toInt().coerceIn(0, 255),
                ),
            )
            is WheelCommand.SetRideMode -> emptyList()
            is WheelCommand.Raw -> listOf(command.bytes)
            else -> emptyList()
        }

    companion object {
        val DEFAULT_CAPABILITIES: WheelCapabilities = WheelCapabilities(
            headlight = true,
            horn = false,
            beep = true,
            ledStrip = true,
            decorativeLights = true,
            rideModes = true,
            maxSpeed = true,
            tiltback = false,
            pedalSensitivity = true,
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
