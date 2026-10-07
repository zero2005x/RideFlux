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
    private val profile: VeteranProtocolProfile = VeteranProtocolProfile.UNKNOWN,
    private val seriesCells: () -> Int? = { null },
) : WheelCodec {

    override val family: WheelFamily = WheelFamily.V

    class VeteranState internal constructor(
        internal var effectiveProfile: VeteranProtocolProfile = VeteranProtocolProfile.UNKNOWN,
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

            when (val r = VeteranDecoder.decode(wire, offset = 0, expectCrcAlways = s.expectCrcAlways, profile = s.effectiveProfile)) {
                is VeteranDecoder.DecodeResult.Ok -> {
                    var frame = r.frame

                    // Frame is now fully validated (framing, declared length, and CRC32 all verified).
                    // Only on a verified frame may we resolve an initial UNKNOWN profile.
                    // TooShort, BadCrc, length mismatches or resyncs NEVER alter the profile.
                    if (s.effectiveProfile == VeteranProtocolProfile.UNKNOWN || s.effectiveProfile == VeteranProtocolProfile.LEGACY) {
                        val modernKey = frame.modernHardwareKeyCandidate
                        val legacyKey = frame.legacyHardwareKeyCandidate
                        if (modernKey == "5010" || modernKey == "5020") {
                            s.effectiveProfile = VeteranProtocolProfile.MODERN_NOSFET
                            val redecoded = VeteranDecoder.decode(wire, offset = 0, expectCrcAlways = s.expectCrcAlways, profile = VeteranProtocolProfile.MODERN_NOSFET)
                            if (redecoded is VeteranDecoder.DecodeResult.Ok) {
                                frame = redecoded.frame
                            }
                        } else {
                            val legacyModel = VeteranModelRegistry.lookup(legacyKey)
                            if (legacyModel != null && !legacyModel.isFirmwareSentinel) {
                                s.effectiveProfile = VeteranProtocolProfile.LEGACY
                                val redecoded = VeteranDecoder.decode(wire, offset = 0, expectCrcAlways = s.expectCrcAlways, profile = VeteranProtocolProfile.LEGACY)
                                if (redecoded is VeteranDecoder.DecodeResult.Ok) {
                                    frame = redecoded.frame
                                }
                            }
                        }
                    }

                    repeat(r.consumedBytes) { s.buffer.removeAt(0) }
                    if (frame.crc32Present) s.expectCrcAlways = true

                    val model = frame.hardwareKey?.let { VeteranModelRegistry.lookup(it) }
                    val modelName = when (s.effectiveProfile) {
                        VeteranProtocolProfile.MODERN_NOSFET -> {
                            when {
                                model != null -> "Nosfet ${model.name}"
                                !frame.hardwareKey.isNullOrBlank() -> "Nosfet (${frame.hardwareKey})"
                                else -> "Nosfet"
                            }
                        }
                        VeteranProtocolProfile.LEGACY -> model?.name ?: "Veteran"
                        VeteranProtocolProfile.UNKNOWN -> "Veteran"
                    }

                    if (!s.identified) {
                        s.identified = true
                        events.add(
                            DecodeEvent.Identified(
                                identity = WheelIdentity(
                                    address = deviceAddress,
                                    family = WheelFamily.V,
                                    modelName = modelName,
                                    firmwareVersion = frame.firmwareVersionString,
                                ),
                                capabilities = DEFAULT_CAPABILITIES,
                            ),
                        )
                    }

                    val soc = VeteranModelRegistry.stateOfCharge(
                        hardwareCode = frame.hardwareKey,
                        packCentiVolts = frame.voltageHundredthsV,
                        userSeriesCells = seriesCells(),
                    )

                    val now = System.currentTimeMillis()
                    val merged = s.last.copy(
                        timestampMillis = now,
                        voltageV = frame.voltageVolts.toFloat(),
                        speedKmh = frame.speedKmh.toFloat(),
                        tripDistanceMetres = frame.tripMeters.toInt(),
                        totalDistanceMetres = frame.totalMeters,
                        // Power train: offset 16 is motor phase current.
                        // In modern Nosfet: /10. In legacy: /100. In UNKNOWN: null.
                        // Pack current `currentA` remains null (project rule 2, no arbitration).
                        phaseCurrentA = frame.phaseCurrentAmps?.toFloat(),
                        mosTemperatureC = frame.temperatureCelsius.toFloat(),
                        pwmPercent = frame.hardwarePwmPercent?.toFloat(),
                        pitchAngleDegrees = frame.pitchAngleDegrees.toFloat(),
                        batteryPercent = soc?.toFloat(),
                        // Ride modes: pedalsMode is read, but integer-to-mode mapping is unverified
                        // and command dispatch is strictly read-only, so `rideMode` remains null.
                        chargingState = when (frame.chargeStatus) {
                            VeteranFrame.ChargeStatus.IDLE -> ChargingState.NOT_CONNECTED
                            VeteranFrame.ChargeStatus.CHARGING -> ChargingState.CHARGING
                            VeteranFrame.ChargeStatus.FULLY_CHARGED -> ChargingState.FULLY_CHARGED
                            is VeteranFrame.ChargeStatus.RESERVED, null -> null
                        },
                    )
                    s.last = merged
                    events.add(DecodeEvent.TelemetryUpdate(merged))

                    // Speed-alert transition → TiltBack.
                    val alertActive = frame.speedAlertTenthsKmh > 0 &&
                        frame.speedTenthsKmh >= frame.speedAlertTenthsKmh
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
        emptyList()

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
            tiltback = false,
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
