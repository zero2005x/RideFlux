/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyv

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.telemetry.ChargingState
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity

/**
 * [WheelCodec] adapter for Family V (Veteran — Sherman, Abrams, …).
 *
 * Family V auto-advertises telemetry frames with no handshake, so
 * [handshakeFrames] is empty. The Veteran spec does not define any
 * host-to-device commands, so every [WheelCommand] returns an empty
 * list.
 */
class VeteranWheelCodec(
    private val deviceAddress: String = "",
) : WheelCodec {

    override val family: WheelFamily = WheelFamily.V

    class VeteranState internal constructor() : WheelCodec.State {
        internal val buffer: ArrayList<Byte> = ArrayList(64)
        internal var last: WheelTelemetry = WheelTelemetry.EMPTY
        internal var identified: Boolean = false
        /** Latches once a CRC frame has been observed (§3.3). */
        internal var expectCrcAlways: Boolean = false
        internal var previousSpeedAlertActive: Boolean = false
    }

    override fun newState(): WheelCodec.State = VeteranState()

    override fun handshakeFrames(state: WheelCodec.State): List<ByteArray> = emptyList()

    override fun decode(state: WheelCodec.State, bytes: ByteArray): List<DecodeEvent> {
        val s = state as VeteranState
        for (b in bytes) s.buffer.add(b)

        val events = ArrayList<DecodeEvent>()
        var progress = true
        while (progress && s.buffer.isNotEmpty()) {
            progress = false
            val wire = ByteArray(s.buffer.size) { s.buffer[it] }
            when (val r = VeteranDecoder.decode(wire, offset = 0, expectCrcAlways = s.expectCrcAlways)) {
                is VeteranDecoder.DecodeResult.Ok -> {
                    repeat(r.consumedBytes) { s.buffer.removeAt(0) }
                    if (r.frame.crc32Present) s.expectCrcAlways = true

                    if (!s.identified) {
                        s.identified = true
                        events.add(
                            DecodeEvent.Identified(
                                identity = WheelIdentity(
                                    address = deviceAddress,
                                    family = WheelFamily.V,
                                    modelName = "Veteran",
                                    firmwareVersion = r.frame.firmwareVersionString,
                                ),
                                capabilities = DEFAULT_CAPABILITIES,
                            ),
                        )
                    }

                    val now = System.currentTimeMillis()
                    val merged = s.last.copy(
                        timestampMillis = now,
                        voltageV = r.frame.voltageVolts.toFloat(),
                        speedKmh = r.frame.speedKmh.toFloat(),
                        tripDistanceMetres = r.frame.tripMeters.toInt(),
                        totalDistanceMetres = r.frame.totalMeters,
                        phaseCurrentA = r.frame.phaseCurrentAmps.toFloat(),
                        mosTemperatureC = r.frame.temperatureCelsius.toFloat(),
                        pwmPercent = r.frame.hardwarePwmPercent.toFloat(),
                        pitchAngleDegrees = r.frame.pitchAngleDegrees.toFloat(),
                        chargingState = when (r.frame.chargeStatus) {
                            VeteranFrame.ChargeStatus.IDLE -> ChargingState.NOT_CONNECTED
                            VeteranFrame.ChargeStatus.CHARGING -> ChargingState.CHARGING
                            VeteranFrame.ChargeStatus.FULLY_CHARGED -> ChargingState.FULLY_CHARGED
                            VeteranFrame.ChargeStatus.RESERVED -> null
                        },
                    )
                    s.last = merged
                    events.add(DecodeEvent.TelemetryUpdate(merged))

                    // Speed-alert transition → TiltBack.
                    val alertActive = r.frame.speedAlertTenthsKmh > 0 &&
                        r.frame.speedTenthsKmh >= r.frame.speedAlertTenthsKmh
                    if (alertActive && !s.previousSpeedAlertActive) {
                        events.add(
                            DecodeEvent.Alert(
                                WheelAlert.TiltBack(
                                    timestampMillis = now,
                                    speedKmh = r.frame.speedKmh.toFloat(),
                                    limit = r.frame.speedTiltbackKmh.toFloat(),
                                ),
                            ),
                        )
                    }
                    s.previousSpeedAlertActive = alertActive

                    progress = true
                }
                is VeteranDecoder.DecodeResult.Fail -> {
                    when (r.error) {
                        is VeteranDecoder.DecodeError.TooShort -> {
                            // Need more bytes; stop looping.
                        }
                        else -> {
                            // Resync one byte at a time.
                            s.buffer.removeAt(0)
                            events.add(
                                DecodeEvent.Malformed(
                                    reason = "Veteran: ${r.error}",
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
            is WheelCommand.Raw -> listOf(command.bytes)
            else -> emptyList()
        }

    companion object {
        val DEFAULT_CAPABILITIES: WheelCapabilities = WheelCapabilities(
            headlight = false,
            horn = false,
            beep = false,
            ledStrip = false,
            decorativeLights = false,
            rideModes = false,
            maxSpeed = false,
            tiltback = false,
            pedalSensitivity = false,
            pedalHorizontal = false,
            calibration = false,
            powerOff = false,
            volume = false,
            playSound = false,
            pinUnlock = false,
            asyncAlerts = false,
        )
    }
}
