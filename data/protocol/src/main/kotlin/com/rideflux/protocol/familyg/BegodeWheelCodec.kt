/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyg

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.telemetry.TelemetryField
import com.rideflux.domain.telemetry.WheelAlert
import com.rideflux.domain.telemetry.WheelFault
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.BegodeProtocolProfile
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
 *
 * @param seriesCells the number of cells in series of this wheel's battery, as
 *   stated by the rider, or `null` while unknown. Read on every frame so a
 *   change applies immediately. The frame carries only a voltage, and the same
 *   voltage is a full 16-cell pack or a nearly empty 20-cell one, so without
 *   this the battery percentage stays unknown (`null`) instead of being guessed.
 */
class BegodeWheelCodec(
    private val deviceAddress: String = "",
    private val seriesCells: () -> Int? = { null },
    private val profile: () -> BegodeProtocolProfile = { BegodeProtocolProfile.UNKNOWN },
) : WheelCodec {

    override val family: WheelFamily = WheelFamily.G

    class BegodeState internal constructor() : WheelCodec.State {
        internal val buffer: ArrayList<Byte> = ArrayList(48)
        internal var last: WheelTelemetry = WheelTelemetry.EMPTY
        internal var identified: Boolean = false
        internal var hasLiveFrame: Boolean = false
        internal var previousFaults: Set<WheelFault> = emptySet()
        internal var appliedProfile: BegodeProtocolProfile? = null
        /** True only after this connection published an in-range packet-0x07 PWM. */
        internal var pwmFromPacket7: Boolean = false
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

            val now = System.currentTimeMillis()
            when (frame) {
                is BegodeFrame.LiveTelemetry -> onLiveTelemetry(s, frame, now, events)
                is BegodeFrame.ExtendedTelemetry -> onExtendedTelemetry(s, frame, now, events)
                is BegodeFrame.SettingsAndOdometer -> onSettingsAndOdometer(s, frame, now, events)
                is BegodeFrame.Unknown -> {
                    val bms = BegodeBmsDecoder.decode(frameBytes)
                    if (bms is BegodeBmsResult.Raw) {
                        events.add(DecodeEvent.RawBmsFrame(bms.frame))
                    }
                    // Other unknown types remain silent; forward-compatible per §12.
                    // Identification is intentionally NOT emitted here:
                    // an Unknown frame with a valid header/footer but an
                    // unrecognised type code must not be treated as proof
                    // of the wheel's identity/capabilities.
                }
            }
        }
        return events
    }

    private fun onLiveTelemetry(
        s: BegodeState,
        frame: BegodeFrame.LiveTelemetry,
        now: Long,
        events: MutableList<DecodeEvent>,
    ) {
        emitIdentifiedOnce(s, events)
        val active = noteProfile(s)
        // The live frame's current word is phase current, not measured battery
        // current. Leave currentA unset so V × I is not presented as pack power.
        val phaseA = frame.phaseCurrentAmps.toFloat()
        val battery = seriesCells()
            ?.let { BegodeBatteryCurve.percentFor(frame.voltageHundredthsV, it) }
            ?.toFloat()
        val legacyTemp = BegodeTemperature.celsiusMpu6500(frame.imuTempRaw).toFloat()
        val a2Imu = BegodeTemperature.celsiusMpu6050(frame.imuTempRaw).toFloat()
        val invalidated = standingWithdrawals(active, s)
        val pwm = when (active) {
            BegodeProtocolProfile.LEGACY_DOCUMENTED -> frame.pwmPercent.toFloat()
            else -> s.last.pwmPercent
        }
        val mos = if (active == BegodeProtocolProfile.LEGACY_DOCUMENTED) legacyTemp else s.last.mosTemperatureC
        val imu = when (active) {
            BegodeProtocolProfile.LEGACY_DOCUMENTED -> legacyTemp
            BegodeProtocolProfile.A2_CAPTURED -> a2Imu
            BegodeProtocolProfile.UNKNOWN -> s.last.imuTemperatureC
        }
        publish(
            s,
            s.last.copy(
                timestampMillis = now,
                speedKmh = frame.speedKmh.toFloat(),
                tripDistanceMetres = frame.tripMeters.toInt(),
                voltageV = frame.voltageVolts.toFloat(),
                phaseCurrentA = phaseA,
                pwmPercent = pwm,
                mosTemperatureC = mos,
                imuTemperatureC = imu,
                batteryPercent = battery,
            ),
            invalidated,
            events,
        )
        s.hasLiveFrame = true
    }

    private fun onExtendedTelemetry(
        s: BegodeState,
        frame: BegodeFrame.ExtendedTelemetry,
        now: Long,
        events: MutableList<DecodeEvent>,
    ) {
        val active = noteProfile(s)
        if (active != BegodeProtocolProfile.A2_CAPTURED) {
            // Packet 0x07 is not evidence for any other layout. Still publish the
            // standing withdrawals so a just-cleared profile cannot keep A2 fields.
            publish(
                s,
                s.last.copy(timestampMillis = now),
                standingWithdrawals(active, s),
                events,
            )
            return
        }
        emitIdentifiedOnce(s, events)
        val pwm = frame.pwmPercentOrNull
        val motor = frame.motorTemperatureCOrNull
        // An out-of-range word withdraws the previous in-range reading.
        s.pwmFromPacket7 = pwm != null
        val invalidated = standingWithdrawals(active, s).toMutableSet()
        if (pwm == null) invalidated += TelemetryField.PWM
        if (motor == null) invalidated += TelemetryField.MOTOR_TEMPERATURE
        publish(
            s,
            s.last.copy(
                timestampMillis = now,
                pwmPercent = pwm ?: s.last.pwmPercent,
                motorTemperatureC = motor ?: s.last.motorTemperatureC,
            ),
            invalidated,
            events,
        )
    }

    private fun standingWithdrawals(
        active: BegodeProtocolProfile,
        s: BegodeState,
    ): Set<TelemetryField> = buildSet {
        add(TelemetryField.CURRENT)
        when (active) {
            BegodeProtocolProfile.UNKNOWN -> {
                add(TelemetryField.PWM)
                add(TelemetryField.MOS_TEMPERATURE)
                add(TelemetryField.IMU_TEMPERATURE)
                add(TelemetryField.MOTOR_TEMPERATURE)
            }
            BegodeProtocolProfile.A2_CAPTURED -> {
                add(TelemetryField.MOS_TEMPERATURE)
                if (!s.pwmFromPacket7) add(TelemetryField.PWM)
            }
            // Packet 0x07 motor temperature is A2-only. Do not keep it under the
            // documented live-frame mapping.
            BegodeProtocolProfile.LEGACY_DOCUMENTED -> add(TelemetryField.MOTOR_TEMPERATURE)
        }
    }

    private fun noteProfile(s: BegodeState): BegodeProtocolProfile {
        val active = profile()
        if (s.appliedProfile != active) {
            s.appliedProfile = active
            s.pwmFromPacket7 = false
            s.last = s.last.copy(
                pwmPercent = null,
                mosTemperatureC = null,
                imuTemperatureC = null,
                motorTemperatureC = null,
                currentA = null,
            )
        }
        return active
    }

    private fun publish(
        s: BegodeState,
        snapshot: WheelTelemetry,
        invalidated: Set<TelemetryField>,
        events: MutableList<DecodeEvent>,
    ) {
        val cleared = snapshot.copy(
            currentA = if (TelemetryField.CURRENT in invalidated) null else snapshot.currentA,
            pwmPercent = if (TelemetryField.PWM in invalidated) null else snapshot.pwmPercent,
            mosTemperatureC = if (TelemetryField.MOS_TEMPERATURE in invalidated) null else snapshot.mosTemperatureC,
            motorTemperatureC = if (TelemetryField.MOTOR_TEMPERATURE in invalidated) null else snapshot.motorTemperatureC,
            imuTemperatureC = if (TelemetryField.IMU_TEMPERATURE in invalidated) null else snapshot.imuTemperatureC,
        )
        s.last = cleared
        events.add(DecodeEvent.TelemetryUpdate(cleared, invalidated))
    }

    private fun onSettingsAndOdometer(
        s: BegodeState,
        frame: BegodeFrame.SettingsAndOdometer,
        now: Long,
        events: MutableList<DecodeEvent>,
    ) {
        emitIdentifiedOnce(s, events)
        val faults = buildFaults(frame)
        publish(
            s,
            s.last.copy(
                timestampMillis = now,
                totalDistanceMetres = frame.totalDistanceMeters,
                faults = faults,
            ),
            standingWithdrawals(noteProfile(s), s),
            events,
        )

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
        // Specific one-shot alerts. Guarded on having seen at least one
        // LiveTelemetry frame so the reported voltage/speed come from real
        // readings rather than placeholder zeros. `previousFaults` is
        // updated only after the alerts have been emitted, because
        // emitOneShotAlerts compares against the *previous* fault set.
        if (s.hasLiveFrame) {
            emitOneShotAlerts(s, frame, now, events)
            s.previousFaults = faults
        }
    }

    private fun emitOneShotAlerts(
        s: BegodeState,
        frame: BegodeFrame.SettingsAndOdometer,
        now: Long,
        events: MutableList<DecodeEvent>,
    ) {
        if (frame.stateOverTemperature && !previousBit(s, WheelFault.MosOverTemperature)) {
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
        if (frame.stateLowVoltage && !previousBit(s, WheelFault.LowBattery)) {
            events.add(
                DecodeEvent.Alert(
                    WheelAlert.LowBattery(
                        timestampMillis = now,
                        voltageV = s.last.voltageV ?: 0f,
                    ),
                ),
            )
        }
        // No TiltBack alert: it used to fire on state bits 1/2, which are a
        // MOS and a gyro fault, and no state bit is known to signal tilt-back.
    }

    private fun emitIdentifiedOnce(s: BegodeState, events: MutableList<DecodeEvent>) {
        if (s.identified) return
        s.identified = true
        events.add(identifiedEvent())
    }

    private fun identifiedEvent(): DecodeEvent.Identified =
        DecodeEvent.Identified(
            identity = WheelIdentity(
                address = deviceAddress,
                family = WheelFamily.G,
                modelName = "Begode",
            ),
            capabilities = DEFAULT_CAPABILITIES,
        )

    override fun encode(state: WheelCodec.State, command: WheelCommand): List<ByteArray> =
        when (command) {
            is WheelCommand.Beep -> listOf(BegodeCommandBuilder.beep())
            is WheelCommand.SetHeadlight -> listOf(
                BegodeCommandBuilder.lightMode(
                    if (command.on) BegodeCommandBuilder.LightMode.ON
                    else BegodeCommandBuilder.LightMode.OFF,
                ),
            )
            is WheelCommand.Calibrate -> {
                val cal = BegodeCommandBuilder.wheelCalibration()
                listOf(cal.first, cal.second)
            }
            is WheelCommand.Raw -> listOf(command.bytes)
            else -> emptyList()
        }

    private fun previousBit(s: BegodeState, fault: WheelFault): Boolean =
        fault in s.previousFaults

    /**
     * Maps the byte-14 state bits to faults. Bit 0 (a high-power warning)
     * and bit 7 (locked, a state the rider chose) are deliberately not faults.
     */
    private fun buildFaults(f: BegodeFrame.SettingsAndOdometer): Set<WheelFault> {
        val set = LinkedHashSet<WheelFault>()
        if (f.stateOverVoltage) set.add(WheelFault.OverVoltage)
        if (f.stateLowVoltage) set.add(WheelFault.LowBattery)
        if (f.stateOverTemperature) set.add(WheelFault.MosOverTemperature)
        if (f.stateHallSensor) set.add(WheelFault.MotorHallSensor)
        if (f.stateGyroFault) set.add(WheelFault.ImuSensor)
        // WheelFault has no MOS/driver-fault variant; OverCurrent would be a guess.
        if (f.stateMosFault) set.add(MOS_FAULT)
        return set
    }

    companion object {
        /** State bit 1: MOS/driver fault. */
        val MOS_FAULT: WheelFault = WheelFault.Unknown(domain = "G.stateBit1.mosFault", code = 1)

        // Capabilities mirror exactly what encode() can emit. Ride modes,
        // tilt-back and pedal sensitivity are advertised as unsupported
        // until the corresponding command mappings are implemented, so a
        // UI built on these flags never exposes controls that always fail.
        val DEFAULT_CAPABILITIES: WheelCapabilities = WheelCapabilities(
            headlight = true,
            horn = false,
            beep = true,
            ledStrip = false,
            decorativeLights = false,
            rideModes = false,
            maxSpeed = false,
            tiltback = false,
            pedalSensitivity = false,
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
