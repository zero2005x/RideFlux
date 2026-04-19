/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyg

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelFault
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity

/**
 * [WheelCodec] adapter for Family G (Begode / Gotway / ExtremeBull).
 *
 * Wraps the stateless [BegodeDecoder] and [BegodeCommandBuilder].
 * Because G frames are fixed 24-byte payloads, the state carries a
 * small reassembly buffer so partial BLE notifications can be
 * accumulated.
 */
class BegodeWheelCodec(
    private val deviceAddress: String = "",
) : WheelCodec {

    override val family: WheelFamily = WheelFamily.G

    class BegodeState internal constructor() : WheelCodec.State {
        internal val buffer: ArrayList<Byte> = ArrayList(48)
        internal var last: WheelTelemetry = WheelTelemetry.EMPTY
        internal var identified: Boolean = false
        internal var previousFaults: Set<WheelFault> = emptySet()
    }

    override fun newState(): WheelCodec.State = BegodeState()

    override fun handshakeFrames(state: WheelCodec.State): List<ByteArray> = listOf(
        BegodeCommandBuilder.requestName(),
        BegodeCommandBuilder.requestFirmware(),
    )

    override fun decode(state: WheelCodec.State, bytes: ByteArray): List<DecodeEvent> {
        val s = state as BegodeState
        for (b in bytes) s.buffer.add(b)

        val events = ArrayList<DecodeEvent>()
        while (s.buffer.size >= 24) {
            val frameBytes = ByteArray(24) { s.buffer[it] }
            val frame = BegodeDecoder.decode(frameBytes)
            if (frame == null) {
                // Resync one byte at a time.
                s.buffer.removeAt(0)
                continue
            }
            // Consume 24 bytes regardless of variant.
            repeat(24) { s.buffer.removeAt(0) }

            if (!s.identified) {
                s.identified = true
                events.add(
                    DecodeEvent.Identified(
                        identity = WheelIdentity(
                            address = deviceAddress,
                            family = WheelFamily.G,
                            modelName = "Begode",
                        ),
                        capabilities = DEFAULT_CAPABILITIES,
                    ),
                )
            }

            val now = System.currentTimeMillis()
            when (frame) {
                is BegodeFrame.LiveTelemetry -> {
                    val merged = s.last.copy(
                        timestampMillis = now,
                        speedKmh = frame.speedKmh.toFloat(),
                        tripDistanceMetres = frame.tripMeters.toInt(),
                        voltageV = frame.voltageVolts.toFloat(),
                        phaseCurrentA = frame.phaseCurrentAmps.toFloat(),
                        pwmPercent = frame.pwmPercent.toFloat(),
                        imuTemperatureC = BegodeTemperature
                            .celsiusMpu6500(frame.imuTempRaw).toFloat(),
                    )
                    s.last = merged
                    events.add(DecodeEvent.TelemetryUpdate(merged))
                }
                is BegodeFrame.SettingsAndOdometer -> {
                    val faults = buildFaults(frame)
                    val merged = s.last.copy(
                        timestampMillis = now,
                        totalDistanceMetres = frame.totalDistanceMeters,
                        faults = faults,
                    )
                    s.last = merged
                    events.add(DecodeEvent.TelemetryUpdate(merged))

                    // Fault-set change alert.
                    val added = faults - s.previousFaults
                    val removed = s.previousFaults - faults
                    if (added.isNotEmpty() || removed.isNotEmpty()) {
                        events.add(
                            DecodeEvent.Alert(
                                WheelAlert.FaultSetChanged(now, added, removed),
                            ),
                        )
                    }
                    // Specific one-shot alerts.
                    if (frame.alertOverTemperature && !previousBit(s, WheelFault.MosOverTemperature)) {
                        events.add(
                            DecodeEvent.Alert(
                                WheelAlert.OverTemperature(
                                    timestampMillis = now,
                                    source = WheelAlert.OverTemperature.Source.MOS,
                                    temperatureC = null,
                                ),
                            ),
                        )
                    }
                    if (frame.alertLowVoltage && !previousBit(s, WheelFault.LowBattery)) {
                        events.add(
                            DecodeEvent.Alert(
                                WheelAlert.LowBattery(
                                    timestampMillis = now,
                                    voltageV = s.last.voltageV ?: 0f,
                                ),
                            ),
                        )
                    }
                    if ((frame.alertSpeedLevel1 || frame.alertSpeedLevel2) &&
                        !previousBit(s, WheelFault.OverSpeed)
                    ) {
                        events.add(
                            DecodeEvent.Alert(
                                WheelAlert.TiltBack(
                                    timestampMillis = now,
                                    speedKmh = s.last.speedKmh ?: 0f,
                                    limit = frame.tiltbackSpeedKmh.toFloat(),
                                ),
                            ),
                        )
                    }
                    s.previousFaults = faults
                }
                is BegodeFrame.Unknown -> {
                    // Nothing to emit; forward-compatible per §12.
                }
            }
        }
        return events
    }

    override fun encode(state: WheelCodec.State, command: WheelCommand): List<ByteArray> =
        when (command) {
            is WheelCommand.Beep -> listOf(BegodeCommandBuilder.beep())
            is WheelCommand.SetHeadlight -> listOf(
                BegodeCommandBuilder.lightMode(
                    if (command.on) BegodeCommandBuilder.LightMode.ON
                    else BegodeCommandBuilder.LightMode.OFF,
                ),
            )
            is WheelCommand.Calibrate -> listOf(BegodeCommandBuilder.wheelCalibration())
            is WheelCommand.Raw -> listOf(command.bytes)
            else -> emptyList()
        }

    private fun previousBit(s: BegodeState, fault: WheelFault): Boolean =
        fault in s.previousFaults

    private fun buildFaults(f: BegodeFrame.SettingsAndOdometer): Set<WheelFault> {
        val set = LinkedHashSet<WheelFault>()
        if (f.alertOverVoltage) set.add(WheelFault.OverVoltage)
        if (f.alertLowVoltage) set.add(WheelFault.LowBattery)
        if (f.alertOverTemperature) set.add(WheelFault.MosOverTemperature)
        if (f.alertHallSensor) set.add(WheelFault.MotorHallSensor)
        if (f.alertSpeedLevel1 || f.alertSpeedLevel2) set.add(WheelFault.OverSpeed)
        return set
    }

    companion object {
        val DEFAULT_CAPABILITIES: WheelCapabilities = WheelCapabilities(
            headlight = true,
            horn = false,
            beep = true,
            ledStrip = false,
            decorativeLights = false,
            rideModes = true,
            maxSpeed = false,
            tiltback = true,
            pedalSensitivity = true,
            pedalHorizontal = false,
            calibration = true,
            powerOff = false,
            volume = false,
            playSound = false,
            pinUnlock = false,
            asyncAlerts = false,
        )
    }
}
