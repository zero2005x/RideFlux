/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyn

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity

/**
 * Shared [WheelCodec] adapter logic for Ninebot Families N1 and N2.
 *
 * The runtime family must be passed to the constructor; the domain
 * layer distinguishes N1 and N2, but the protocol layer shares a
 * single frame/decoder pair driven by [NinebotFamily].
 *
 * For N2, the session keystream is negotiated via [NinebotCommandBuilder.getKey];
 * once the 16-byte reply is observed, subsequent traffic uses that
 * γ. Until then (and for N1 always), the all-zero keystream applies.
 */
abstract class NinebotWheelCodecBase internal constructor(
    private val deviceAddress: String,
    private val domainFamily: WheelFamily,
    private val wireFamily: NinebotFamily,
) : WheelCodec {

    override val family: WheelFamily get() = domainFamily

    class NinebotState internal constructor(initialGamma: ByteArray) : WheelCodec.State {
        internal val buffer: ArrayList<Byte> = ArrayList(64)
        internal var last: WheelTelemetry = WheelTelemetry.EMPTY
        internal var identified: Boolean = false
        internal var gamma: ByteArray = initialGamma
    }

    override fun newState(): WheelCodec.State =
        NinebotState(initialGamma = NinebotCodec.ZERO_KEYSTREAM.copyOf())

    override fun handshakeFrames(state: WheelCodec.State): List<ByteArray> =
        if (wireFamily == NinebotFamily.N2) {
            listOf(NinebotCommandBuilder.getKey())
        } else {
            emptyList()
        }

    override fun decode(state: WheelCodec.State, bytes: ByteArray): List<DecodeEvent> {
        val s = state as NinebotState
        for (b in bytes) s.buffer.add(b)

        val events = ArrayList<DecodeEvent>()
        var progress = true
        while (progress && s.buffer.size >= 2) {
            progress = false
            val wire = ByteArray(s.buffer.size) { s.buffer[it] }
            when (val r = NinebotDecoder.decode(wire, wireFamily, s.gamma, offset = 0)) {
                is NinebotDecodeResult.Ok -> {
                    repeat(r.consumedBytes) { s.buffer.removeAt(0) }
                    progress = true

                    // N2 γ capture on GetKey reply.
                    if (wireFamily == NinebotFamily.N2 &&
                        r.frame.cmd == NinebotCommandBuilder.CMD_GET_KEY &&
                        r.frame.data.size == NinebotCodec.KEYSTREAM_SIZE
                    ) {
                        s.gamma = r.frame.data.copyOf()
                    }

                    if (!s.identified) {
                        s.identified = true
                        events.add(
                            DecodeEvent.Identified(
                                identity = WheelIdentity(
                                    address = deviceAddress,
                                    family = domainFamily,
                                    modelName = if (wireFamily == NinebotFamily.N2) "Ninebot N2" else "Ninebot N1",
                                ),
                                capabilities = DEFAULT_CAPABILITIES,
                            ),
                        )
                    }

                    // §3.4.1 telemetry page at PARAM = 0xB0 (N2) or direct push (N1).
                    if (r.frame.param == 0xB0 && r.frame.data.size >= NinebotTelemetryB0.MIN_DATA_SIZE) {
                        val t = NinebotTelemetryB0.parse(r.frame.data)
                        val now = System.currentTimeMillis()
                        val merged = s.last.copy(
                            timestampMillis = now,
                            batteryPercent = t.batteryPercent.toFloat(),
                            speedKmh = t.speedStandardKmh.toFloat(),
                            totalDistanceMetres = t.totalDistanceMetres,
                            mosTemperatureC = t.temperatureCelsius.toFloat(),
                            voltageV = t.voltageVolts.toFloat(),
                            currentA = t.currentAmps.toFloat(),
                        )
                        s.last = merged
                        events.add(DecodeEvent.TelemetryUpdate(merged))
                    }
                }
                is NinebotDecodeResult.Fail -> {
                    when (r.error) {
                        is NinebotDecodeError.TooShort -> {
                            // Wait for more bytes.
                        }
                        else -> {
                            s.buffer.removeAt(0)
                            events.add(
                                DecodeEvent.Malformed(
                                    reason = "Ninebot ${wireFamily}: ${r.error}",
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

/** [WheelCodec] adapter for Family N1 (short-frame Ninebot). */
class NinebotN1WheelCodec(deviceAddress: String = "") :
    NinebotWheelCodecBase(deviceAddress, WheelFamily.N1, NinebotFamily.N1)

/** [WheelCodec] adapter for Family N2 (long-frame Ninebot with γ handshake). */
class NinebotN2WheelCodec(deviceAddress: String = "") :
    NinebotWheelCodecBase(deviceAddress, WheelFamily.N2, NinebotFamily.N2)
