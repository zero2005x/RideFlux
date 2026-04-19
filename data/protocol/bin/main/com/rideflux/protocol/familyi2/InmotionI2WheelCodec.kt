/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyi2

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
 * [WheelCodec] adapter for Family I2 (Inmotion V11 / V12 / V13 /
 * V14 modern application protocol).
 *
 * Handshake follows §9.5.2: request car-type, serial, version. After
 * `Ready`, the transport layer should drive [keepAliveFrames] at the
 * §9.5.2 25 ms ping cadence.
 *
 * Variant-specific real-time telemetry offsets (§8.8) are not yet
 * resolved here — only the universal "core electrical" fields
 * (voltage @0, current @2) are decoded from the real-time frame. The
 * 7-byte error bitmap offset is variant-dependent; this adapter
 * looks for an error bitmap starting at DATA offset 48 when DATA is
 * long enough (the V11 ≥ 1.4 layout). Callers that need other
 * variants can subclass or replace.
 */
class InmotionI2WheelCodec(
    private val deviceAddress: String = "",
) : WheelCodec {

    override val family: WheelFamily = WheelFamily.I2

    class InmotionI2State internal constructor() : WheelCodec.State {
        internal val buffer: ArrayList<Byte> = ArrayList(96)
        internal var last: WheelTelemetry = WheelTelemetry.EMPTY
        internal var identified: Boolean = false
        internal var carType: String? = null
        internal var serial: String? = null
        internal var firmware: String? = null
        internal var previousFaults: Set<WheelFault> = emptySet()
        /** Byte offset of the `E0..E6` error bitmap within the real-time DATA. */
        internal var errorBitmapOffset: Int = 48
    }

    override fun newState(): WheelCodec.State = InmotionI2State()

    override fun handshakeFrames(state: WheelCodec.State): List<ByteArray> = listOf(
        InmotionI2CommandBuilder.requestCarType(),
        InmotionI2CommandBuilder.requestSerial(),
        InmotionI2CommandBuilder.requestVersion(),
    )

    override fun keepAliveFrames(state: WheelCodec.State): List<ByteArray> =
        listOf(InmotionI2CommandBuilder.ping())

    override val keepAlivePeriodMillis: Long = 25L

    override fun decode(state: WheelCodec.State, bytes: ByteArray): List<DecodeEvent> {
        val s = state as InmotionI2State
        for (b in bytes) s.buffer.add(b)

        val events = ArrayList<DecodeEvent>()
        var progress = true
        while (progress && s.buffer.size >= 5) {
            progress = false
            val wire = ByteArray(s.buffer.size) { s.buffer[it] }
            when (val r = InmotionI2Decoder.decode(wire, offset = 0)) {
                is InmotionI2DecodeResult.Ok -> {
                    repeat(r.consumedBytes) { s.buffer.removeAt(0) }
                    progress = true

                    val frame = r.frame
                    val now = System.currentTimeMillis()
                    when (frame.cmd) {
                        InmotionI2CommandBuilder.CMD_MAIN_INFO -> handleMainInfo(s, frame)
                        InmotionI2CommandBuilder.CMD_REALTIME -> {
                            val snapshotEvent = handleRealtime(s, frame, now)
                            if (snapshotEvent != null) events.add(snapshotEvent)
                            maybeEmitFaultChange(s, now)?.let { events.add(it) }
                        }
                        else -> Unit
                    }

                    maybeEmitIdentified(s, events)
                }
                is InmotionI2DecodeResult.Fail -> {
                    when (r.error) {
                        is InmotionI2DecodeError.TooShort -> {
                            // Wait for more bytes.
                        }
                        else -> {
                            s.buffer.removeAt(0)
                            events.add(
                                DecodeEvent.Malformed(
                                    reason = "I2: ${r.error}",
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
            is WheelCommand.SetHeadlight -> listOf(InmotionI2CommandBuilder.setHeadlight(command.on))
            is WheelCommand.SetDecorativeLights -> listOf(
                InmotionI2CommandBuilder.setDaytimeRunningLight(command.on),
            )
            is WheelCommand.SetMaxSpeedKmh -> listOf(
                InmotionI2CommandBuilder.setMaxSpeed(command.kmh.toDouble()),
            )
            is WheelCommand.SetPedalSensitivity -> listOf(
                InmotionI2CommandBuilder.setPedalSensitivity(command.level.coerceIn(0, 255)),
            )
            is WheelCommand.SetPedalHorizontal -> listOf(
                InmotionI2CommandBuilder.setPedalHorizonTilt(command.angleDegrees.toDouble()),
            )
            is WheelCommand.SetRideMode -> listOf(
                InmotionI2CommandBuilder.setRideModeClassic(command.modeCode != 0),
            )
            is WheelCommand.SetVolume -> listOf(InmotionI2CommandBuilder.setVolume(command.percent))
            is WheelCommand.PlaySound -> listOf(InmotionI2CommandBuilder.playSound(command.soundId))
            is WheelCommand.Calibrate -> listOf(InmotionI2CommandBuilder.calibration())
            // Two-stage power-off handled internally: caller dispatches
            // once, both frames emit. The connection should write them
            // back-to-back; stage 2 is expected to be ignored if the
            // wheel has already obeyed stage 1.
            is WheelCommand.PowerOff -> listOf(
                InmotionI2CommandBuilder.powerOffStage1(),
                InmotionI2CommandBuilder.powerOffStage2(),
            )
            is WheelCommand.Raw -> listOf(command.bytes)
            else -> emptyList()
        }

    // ---- helpers --------------------------------------------------------

    private fun handleMainInfo(s: InmotionI2State, frame: InmotionI2Frame) {
        if (frame.data.isEmpty()) return
        val selector = frame.data[0].toInt() and 0xFF
        val payload = frame.data.copyOfRange(1, frame.data.size)
        when (selector) {
            InmotionI2CommandBuilder.MAIN_INFO_CAR_TYPE -> {
                s.carType = payload.toAsciiTrim()
            }
            InmotionI2CommandBuilder.MAIN_INFO_SERIAL -> {
                s.serial = payload.toAsciiTrim()
            }
            InmotionI2CommandBuilder.MAIN_INFO_VERSION -> {
                s.firmware = payload.toAsciiTrim()
            }
        }
    }

    private fun maybeEmitIdentified(s: InmotionI2State, events: MutableList<DecodeEvent>) {
        if (s.identified) return
        // Emit once any identification field is known.
        if (s.carType == null && s.serial == null && s.firmware == null) return
        s.identified = true
        events.add(
            DecodeEvent.Identified(
                identity = WheelIdentity(
                    address = deviceAddress,
                    family = WheelFamily.I2,
                    modelName = s.carType ?: "Inmotion",
                    serialNumber = s.serial,
                    firmwareVersion = s.firmware,
                ),
                capabilities = DEFAULT_CAPABILITIES,
            ),
        )
    }

    private fun handleRealtime(
        s: InmotionI2State,
        frame: InmotionI2Frame,
        now: Long,
    ): DecodeEvent.TelemetryUpdate? {
        if (frame.data.size < InmotionI2RealtimeCore.MIN_DATA_SIZE) return null
        val core = InmotionI2RealtimeCore.parse(frame.data)

        var merged = s.last.copy(
            timestampMillis = now,
            voltageV = core.voltageV.toFloat(),
            currentA = core.currentA.toFloat(),
        )
        // Optional: V11 early layout decodes more fields.
        if (frame.data.size >= InmotionI2RealtimeV11Early.MIN_DATA_SIZE) {
            val t = InmotionI2RealtimeV11Early.parse(frame.data)
            merged = merged.copy(
                speedKmh = t.speedKmh.toFloat(),
                tripDistanceMetres = t.tripDistanceMetres,
                phaseCurrentA = t.phaseCurrentA.toFloat(),
            )
        }
        // Optional: variant-dependent error bitmap.
        val off = s.errorBitmapOffset
        if (frame.data.size - off >= InmotionI2ErrorBitmap.SIZE) {
            val bmp = InmotionI2ErrorBitmap.parse(frame.data, off)
            merged = merged.copy(faults = faultsFromBitmap(bmp))
        }
        s.last = merged
        return DecodeEvent.TelemetryUpdate(merged)
    }

    private fun maybeEmitFaultChange(s: InmotionI2State, now: Long): DecodeEvent.Alert? {
        val current = s.last.faults
        val added = current - s.previousFaults
        val removed = s.previousFaults - current
        if (added.isEmpty() && removed.isEmpty()) return null
        s.previousFaults = current
        return DecodeEvent.Alert(
            WheelAlert.FaultSetChanged(
                timestampMillis = now,
                added = added,
                removed = removed,
            ),
        )
    }

    private fun faultsFromBitmap(b: InmotionI2ErrorBitmap): Set<WheelFault> {
        val out = LinkedHashSet<WheelFault>()
        if (b.motorHall) out.add(WheelFault.MotorHallSensor)
        if (b.imuSensor) out.add(WheelFault.ImuSensor)
        if (b.mosTemp) out.add(WheelFault.MosOverTemperature)
        if (b.motorTemp) out.add(WheelFault.MotorOverTemperature)
        if (b.batteryTemp) out.add(WheelFault.BatteryOverTemperature)
        if (b.overBoardTemp) out.add(WheelFault.BoardOverTemperature)
        if (b.underVoltage) out.add(WheelFault.UnderVoltage)
        if (b.overVoltage) out.add(WheelFault.OverVoltage)
        if (b.overSpeed) out.add(WheelFault.OverSpeed)
        if (b.motorBlock) out.add(WheelFault.MotorBlocked)
        if (b.riskBehaviour) out.add(WheelFault.RiskBehaviour)
        if (b.lowBatterySeverity != InmotionI2Severity.NONE) out.add(WheelFault.LowBattery)
        if (b.overBusCurrentSeverity != InmotionI2Severity.NONE) out.add(WheelFault.OverCurrent)
        if (b.cpuOverTemp) out.add(WheelFault.Unknown("I2", 0x540))
        if (b.imuOverTemp) out.add(WheelFault.Unknown("I2", 0x580))
        return out
    }

    private fun ByteArray.toAsciiTrim(): String {
        val end = indexOfFirst { it == 0.toByte() }.let { if (it < 0) size else it }
        return String(this, 0, end, Charsets.US_ASCII).trim()
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
            pedalHorizontal = true,
            calibration = true,
            powerOff = true,
            volume = true,
            playSound = true,
            pinUnlock = false,
            asyncAlerts = true,
        )
    }
}
