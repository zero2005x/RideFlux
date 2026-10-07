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
 *
 * [profile] determines the initial wire layout (legacy vs modern Nosfet).
 * When constructed with the default [VeteranProtocolProfile.LEGACY], the codec
 * safely auto-latches to [VeteranProtocolProfile.MODERN_NOSFET] once wire bytes
 * evaluate to verified Nosfet hardware codes ("5010" for Apex, "5020" for Aero).
 * For unverified or legacy hardware codes, the codec remains in its safe initial
 * profile to prevent misinterpreting wire fields. Callers can also supply
 * [VeteranProtocolProfile.MODERN_NOSFET] explicitly.
 */
class VeteranWheelCodec(
    private val deviceAddress: String = "",
    private val profile: VeteranProtocolProfile = VeteranProtocolProfile.LEGACY,
    private val seriesCells: () -> Int? = { null },
) : WheelCodec {

    override val family: WheelFamily = WheelFamily.V

    class VeteranState internal constructor(
        internal var effectiveProfile: VeteranProtocolProfile = VeteranProtocolProfile.LEGACY,
    ) : WheelCodec.State {
        internal val buffer: ArrayList<Byte> = ArrayList(64)
        internal var last: WheelTelemetry = WheelTelemetry.EMPTY
        internal var identified: Boolean = false
        /** Latches once a CRC frame has been observed (§3.3). */
        internal var expectCrcAlways: Boolean = false
        internal var previousSpeedAlertActive: Boolean = false
        internal var hasSpeedAlertBaseline: Boolean = false
    }

    override fun newState(): WheelCodec.State = VeteranState(effectiveProfile = profile)

    override fun handshakeFrames(state: WheelCodec.State): List<ByteArray> = emptyList()

    override fun decode(state: WheelCodec.State, bytes: ByteArray): List<DecodeEvent> {
        val s = state as? VeteranState
            ?: error("VeteranWheelCodec requires VeteranState, got ${state::class.simpleName}")
        val events = ArrayList<DecodeEvent>()
        for (b in bytes) {
            if (s.buffer.size >= MAX_FRAME_BUFFER_SIZE) {
                s.buffer.clear()
                events.add(
                    DecodeEvent.Malformed(
                        reason = "Veteran: frame buffer overflow; resynchronising",
                        offendingBytes = null,
                    ),
                )
            }
            s.buffer.add(b)
        }
        var progress = true
        while (progress && s.buffer.isNotEmpty()) {
            progress = false
            val wire = ByteArray(s.buffer.size) { s.buffer[it] }

            // Latch modern Nosfet profile if modern hardware key (e.g. 5010 for Apex, 5020 for Aero) is detected
            if (s.effectiveProfile == VeteranProtocolProfile.LEGACY && wire.size >= 32) {
                if (wire[0] == 0xDC.toByte() && wire[1] == 0x5A.toByte() && wire[2] == 0x5C.toByte()) {
                    val b30 = wire[30].toInt() and 0xFF
                    val b28 = wire[28].toInt() and 0xFF
                    val b29 = wire[29].toInt() and 0xFF
                    val modernKey = VeteranModelRegistry.modernHardwareKey(b30, b28, b29)
                    if (modernKey == "5010" || modernKey == "5020") {
                        s.effectiveProfile = VeteranProtocolProfile.MODERN_NOSFET
                    }
                }
            }

            when (val r = VeteranDecoder.decode(wire, offset = 0, expectCrcAlways = s.expectCrcAlways, profile = s.effectiveProfile)) {
                is VeteranDecoder.DecodeResult.Ok -> {
                    repeat(r.consumedBytes) { s.buffer.removeAt(0) }
                    if (r.frame.crc32Present) s.expectCrcAlways = true

                    val model = VeteranModelRegistry.lookup(r.frame.hardwareKey)
                    val modelName = if (s.effectiveProfile == VeteranProtocolProfile.MODERN_NOSFET) {
                        when {
                            model != null -> "Nosfet ${model.name}"
                            r.frame.hardwareKey.isNotBlank() -> "Nosfet (${r.frame.hardwareKey})"
                            else -> "Nosfet"
                        }
                    } else {
                        model?.name ?: "Veteran"
                    }

                    if (!s.identified) {
                        s.identified = true
                        events.add(
                            DecodeEvent.Identified(
                                identity = WheelIdentity(
                                    address = deviceAddress,
                                    family = WheelFamily.V,
                                    modelName = modelName,
                                    firmwareVersion = r.frame.firmwareVersionString,
                                ),
                                capabilities = DEFAULT_CAPABILITIES,
                            ),
                        )
                    }

                    val soc = VeteranModelRegistry.stateOfCharge(
                        hardwareCode = r.frame.hardwareKey,
                        packCentiVolts = r.frame.voltageHundredthsV,
                        userSeriesCells = seriesCells(),
                    )

                    val now = System.currentTimeMillis()
                    val merged = s.last.copy(
                        timestampMillis = now,
                        voltageV = r.frame.voltageVolts.toFloat(),
                        speedKmh = r.frame.speedKmh.toFloat(),
                        tripDistanceMetres = r.frame.tripMeters.toInt(),
                        totalDistanceMetres = r.frame.totalMeters,
                        // Power train: offset 16 is motor phase current. The vendor app derives pack
                        // current in software via `(machineCurrent * output) / 10000`, but without live
                        // L3 Bluetooth capture arbitration to confirm scaling and semantics, `currentA`
                        // remains null (project rule 2).
                        phaseCurrentA = r.frame.phaseCurrentAmps.toFloat(),
                        mosTemperatureC = r.frame.temperatureCelsius.toFloat(),
                        pwmPercent = if (s.effectiveProfile == VeteranProtocolProfile.MODERN_NOSFET)
                            null else r.frame.hardwarePwmPercent.toFloat(),
                        pitchAngleDegrees = r.frame.pitchAngleDegrees.toFloat(),
                        batteryPercent = soc?.toFloat(),
                        // Ride modes: decoder reads `pedalsMode` (offset 31 in modern, 30 in legacy), but
                        // integer-to-mode mapping is unverified on live hardware and command dispatch is
                        // strictly read-only, so `rideMode` remains null and DEFAULT_CAPABILITIES.rideModes is false.
                        chargingState = when (r.frame.chargeStatus) {
                            VeteranFrame.ChargeStatus.IDLE -> ChargingState.NOT_CONNECTED
                            VeteranFrame.ChargeStatus.CHARGING -> ChargingState.CHARGING
                            VeteranFrame.ChargeStatus.FULLY_CHARGED -> ChargingState.FULLY_CHARGED
                            is VeteranFrame.ChargeStatus.RESERVED -> null
                        },
                    )
                    s.last = merged
                    events.add(DecodeEvent.TelemetryUpdate(merged))

                    // Speed-alert transition → TiltBack.
                    val alertActive = r.frame.speedAlertTenthsKmh > 0 &&
                        r.frame.speedTenthsKmh >= r.frame.speedAlertTenthsKmh
                    if (s.hasSpeedAlertBaseline && alertActive && !s.previousSpeedAlertActive) {
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
                    s.hasSpeedAlertBaseline = true

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
        /** Four-byte header + the largest length byte (which already counts a CRC trailer). */
        internal const val MAX_FRAME_BUFFER_SIZE: Int = 4 + 255

        val DEFAULT_CAPABILITIES: WheelCapabilities = WheelCapabilities(
            headlight = false,
            horn = false,
            beep = false,
            ledStrip = false,
            decorativeLights = false,
            rideModes = false,
            maxSpeed = false,
            tiltback = true,
            pedalSensitivity = false,
            pedalHorizontal = false,
            calibration = false,
            powerOff = false,
            volume = false,
            playSound = false,
            pinUnlock = false,
            asyncAlerts = true,
        )
    }
}
