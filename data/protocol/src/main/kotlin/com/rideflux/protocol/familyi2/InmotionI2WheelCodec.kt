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
import com.rideflux.domain.wheel.WheelControlContract
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity
import com.rideflux.domain.wheel.WheelIdentityEvidence
import com.rideflux.domain.wheel.WheelSupport
import com.rideflux.domain.wheel.WheelTelemetryCoverage
import com.rideflux.protocol.bytes.ByteReader

/**
 * [WheelCodec] adapter for Family I2 (Inmotion V11Y / V12 / V13 /
 * V14 modern application protocol).
 *
 * Handshake follows §9.5.2: request car-type, serial, version. After
 * `Ready`, the transport layer should drive [keepAliveFrames] at the
 * §9.5.2 25 ms ping cadence.
 *
 * ### Identity and support contract
 *
 * A CAR_TYPE response identifies the model, but not the firmware. `Ready` is
 * therefore split from "fully supported":
 *
 *  * blank CAR_TYPE is ignored and reported as [DecodeEvent.Malformed]
 *    (the raw decoder must stay fail-soft);
 *  * an unrecognised non-blank model publishes [WheelSupport.FAMILY_ONLY]
 *    with an all-false capability matrix, so the UI can show a read-only
 *    preview instead of offering controls;
 *  * a recognised model without a decoded firmware version publishes
 *    [WheelIdentityEvidence.MODEL_ONLY] and keeps controls disabled;
 *  * only a recognised model **and** a decoded firmware version publishes the
 *    family's closed command set.
 *
 * The encoder repeats the same gate with the per-connection state, so a
 * command cannot reach the wire even if a caller bypasses the UI.
 *
 * ### Telemetry
 *
 * Offsets 0 (bus voltage) and 2 (signed bus current) are the only fields whose
 * meaning is constant across every I2 variant. Everything else is applied only
 * for an exact model/firmware layout:
 *
 *  * V11 with verified firmware < 1.4: the established early layout;
 *  * V11Y: the vendor-static 74-byte Lorin layout (voltage, current, speed,
 *    battery); its trip/temperature fields are deliberately left `null`
 *    because their scales are not established;
 *  * V11 with firmware >= 1.4: core fields + the known fault bitmap;
 *  * every other recognised model: core fields only.
 */
class InmotionI2WheelCodec(
    private val deviceAddress: String = "",
) : WheelCodec {

    override val family: WheelFamily = WheelFamily.I2

    class InmotionI2State internal constructor() : WheelCodec.State {
        internal val buffer: ArrayList<Byte> = ArrayList(96)
        internal var last: WheelTelemetry = WheelTelemetry.EMPTY
        internal var identified: Boolean = false
        internal var lastEmittedIdentity: WheelIdentity? = null
        internal var carType: String? = null
        internal var serial: String? = null
        internal var firmware: String? = null
        internal var mainBoardMajor: Int? = null
        internal var mainBoardMinor: Int? = null
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
                    if (r.consumedBytes <= 0) {
                        s.buffer.removeAt(0)
                        events.add(
                            DecodeEvent.Malformed(
                                reason = "I2: decoder Ok with 0 consumed bytes",
                                offendingBytes = null,
                            ),
                        )
                        progress = true
                        continue
                    }
                    repeat(r.consumedBytes) { s.buffer.removeAt(0) }
                    progress = true

                    val frame = r.frame
                    val now = System.currentTimeMillis()
                    when (frame.cmd) {
                        InmotionI2CommandBuilder.CMD_MAIN_INFO -> handleMainInfo(s, frame, events)
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

    override fun encode(state: WheelCodec.State, command: WheelCommand): List<ByteArray> {
        val s = state as InmotionI2State
        // The same evidence gate the connection applies after identification.
        // Before a model and firmware are both established the family's
        // command set is not closed for this device, so no control frame may
        // be emitted. Raw is deliberately included: it is a diagnostic escape
        // hatch whose danger tier the connection rejects before encode().
        if (!controlsAllowed(s)) return emptyList()
        return when (command) {
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
    }

    // ---- helpers --------------------------------------------------------

    private fun handleMainInfo(
        s: InmotionI2State,
        frame: InmotionI2Frame,
        events: MutableList<DecodeEvent>,
    ) {
        if (frame.data.isEmpty()) return
        val selector = frame.data[0].toInt() and 0xFF
        val payload = frame.data.copyOfRange(1, frame.data.size)
        when (selector) {
            InmotionI2CommandBuilder.MAIN_INFO_CAR_TYPE -> {
                val model = payload.toAsciiTrim()
                if (model.isBlank()) {
                    // Fail soft at the raw-codec boundary: a blank CAR_TYPE is
                    // malformed wire data, not an identity. Keep any previously
                    // decoded model so a single corrupt frame cannot erase it.
                    events.add(
                        DecodeEvent.Malformed(
                            reason = "I2: blank CAR_TYPE",
                            offendingBytes = frame.data.copyOf(),
                        ),
                    )
                } else {
                    s.carType = model
                }
            }
            InmotionI2CommandBuilder.MAIN_INFO_SERIAL -> {
                s.serial = payload.toAsciiTrim()
            }
            InmotionI2CommandBuilder.MAIN_INFO_VERSION -> {
                // Selector 0x06 version table (§9.5.2 and InmotionAdapterV2.java:578-616).
                // Binary response carries selector at data[0], driver board at [2..5],
                // mainboard version at data[11..14] (MainBoard1=data[14], MainBoard2=data[13], MainBoard3=u16LE @11).
                if (frame.data.size >= 24) {
                    val mb3 = ByteReader.u16LE(frame.data, 11)
                    val mb2 = frame.data[13].toInt() and 0xFF
                    val mb1 = frame.data[14].toInt() and 0xFF
                    s.mainBoardMajor = mb1
                    s.mainBoardMinor = mb2
                    s.firmware = "$mb1.$mb2.$mb3"
                } else {
                    val ascii = payload.toAsciiTrim()
                    val match = Regex("""^(\d+)\.(\d+)""").find(ascii)
                    val major = match?.groupValues?.get(1)?.toIntOrNull()
                    val minor = match?.groupValues?.get(2)?.toIntOrNull()
                    if (major == null || minor == null) {
                        // Do not publish a new firmware string while retaining
                        // the previous parsed major/minor (or vice versa): the
                        // identity, parsed evidence and control contract must
                        // come from the same accepted frame. Keep the last
                        // consistent identity and report the wire anomaly.
                        events.add(
                            DecodeEvent.Malformed(
                                reason = "I2: unparsable firmware '$ascii'",
                                offendingBytes = frame.data.copyOf(),
                            ),
                        )
                    } else {
                        s.firmware = ascii
                        s.mainBoardMajor = major
                        s.mainBoardMinor = minor
                    }
                }
            }
        }
    }

    private fun maybeEmitIdentified(s: InmotionI2State, events: MutableList<DecodeEvent>) {
        val model = s.carType?.takeIf { it.isNotBlank() } ?: return
        val currentIdentity = WheelIdentity(
            address = deviceAddress.ifBlank { "00:00:00:00:00:00" },
            family = WheelFamily.I2,
            modelName = model,
            serialNumber = s.serial,
            firmwareVersion = s.firmware,
        )
        if (!s.identified || currentIdentity != s.lastEmittedIdentity) {
            s.identified = true
            s.lastEmittedIdentity = currentIdentity
            events.add(
                DecodeEvent.Identified(
                    identity = currentIdentity,
                    capabilities = capabilitiesFor(s),
                    support = supportFor(s),
                ),
            )
        }
    }

    private fun handleRealtime(
        s: InmotionI2State,
        frame: InmotionI2Frame,
        now: Long,
    ): DecodeEvent.TelemetryUpdate? {
        if (frame.data.size < InmotionI2RealtimeCore.MIN_DATA_SIZE) return null
        // CAR_TYPE=V11Y can arrive before the firmware reply. Until the
        // firmware is verified the V11Y-specific 74-byte layout is not
        // selected, so publish no realtime snapshot rather than a snapshot
        // built from an unverified layout. This is a layout-selection
        // withhold, not a "safe" reuse of another generation's scale.
        val model = s.carType?.let(InmotionModelRegistry::find)
        if (model?.key == "v11y" && !firmwareVerified(s)) return null
        val core = InmotionI2RealtimeCore.parse(frame.data)

        var merged = s.last.copy(
            timestampMillis = now,
            voltageV = core.voltageV.toFloat(),
            currentA = core.currentA.toFloat(),
        )
        // Speed is fresh only when this record was long enough to decode it.
        var freshSpeed = false
        when {
            // V11 early layout: exact model + verified firmware < 1.4.
            isV11EarlyLayout(s) -> {
                if (frame.data.size >= InmotionI2RealtimeV11Early.MIN_DATA_SIZE) {
                    freshSpeed = true
                    val t = InmotionI2RealtimeV11Early.parse(frame.data)
                    merged = merged.copy(
                        speedKmh = t.speedKmh.toFloat(),
                        tripDistanceMetres = t.tripDistanceMetres,
                        batteryPercent = t.batteryPercent?.toFloat(),
                        mosTemperatureC = t.mosTempCelsius?.toFloat(),
                        pitchAngleDegrees = t.pitchAngleDegrees?.toFloat(),
                        rollAngleDegrees = t.rollAngleDegrees?.toFloat(),
                        // phaseCurrentA remains null: offset 2 is bus current (currentA), not phase current.
                    )
                }
                // Error bitmap at offset 48 is NOT evaluated for early layout (§3.5.4.A).
            }
            // V11Y uses the vendor-static 74-byte Lorin layout. Only fields
            // whose scale is established are published; trip and temperatures
            // stay null rather than being copied from V11 or guessed.
            isV11YLayout(s) && frame.data.size >= InmotionI2RealtimeV11Y.MIN_DATA_SIZE -> {
                // freshSpeed stays false: OFFSET_SPEED (0x06) is the charge-current
                // word, not speed, so this value must not feed the motion interlock.
                // Set it to true together with the V11Y offset correction (speed 0x08).
                val t = InmotionI2RealtimeV11Y.parse(frame.data)
                merged = merged.copy(
                    voltageV = t.voltageV.toFloat(),
                    currentA = t.currentA.toFloat(),
                    speedKmh = t.speedKmh.toFloat(),
                    batteryPercent = t.batteryPercent,
                )
            }
            // Only the V11 >= 1.4 layout has an established error-bitmap
            // offset. Applying offset 48 to another model would decode a
            // plausible fault set from unrelated bytes.
            isV11PostEarly(s) -> {
                val off = s.errorBitmapOffset
                if (frame.data.size - off >= InmotionI2ErrorBitmap.SIZE) {
                    val bmp = InmotionI2ErrorBitmap.parse(frame.data, off)
                    merged = merged.copy(faults = faultsFromBitmap(bmp))
                }
            }
        }
        s.last = merged
        return DecodeEvent.TelemetryUpdate(merged, freshSpeed = freshSpeed)
    }

    /**
     * Exact model lookup prevents V11Y's distinct 74-byte layout from
     * entering the V11 early path. Requires verified FW < 1.4.
     */
    private fun isV11EarlyLayout(s: InmotionI2State): Boolean {
        val model = s.carType?.let { InmotionModelRegistry.find(it) }
        if (model?.key != "v11") return false
        val major = s.mainBoardMajor ?: return false
        val minor = s.mainBoardMinor ?: return false
        return major < 1 || (major == 1 && minor < 4)
    }

    private fun isV11YLayout(s: InmotionI2State): Boolean {
        val model = s.carType?.let { InmotionModelRegistry.find(it) }
        return model?.key == "v11y" && firmwareVerified(s)
    }

    private fun isV11PostEarly(s: InmotionI2State): Boolean {
        val model = s.carType?.let { InmotionModelRegistry.find(it) }
        return model?.key == "v11" && firmwareVerified(s) && !isV11EarlyLayout(s)
    }

    private fun firmwareVerified(s: InmotionI2State): Boolean =
        s.mainBoardMajor != null && s.mainBoardMinor != null

    private fun controlsAllowed(s: InmotionI2State): Boolean {
        val model = s.carType?.let { InmotionModelRegistry.find(it) } ?: return false
        if (model.key.isBlank()) return false
        if (model.generation != InmotionModelRegistry.Generation.LORIN) return false
        if (model.key !in CLOSED_CONTROL_MODELS) return false
        return firmwareVerified(s)
    }

    private fun capabilitiesFor(s: InmotionI2State): WheelCapabilities =
        if (controlsAllowed(s)) DEFAULT_CAPABILITIES else READ_ONLY_CAPABILITIES

    private fun supportFor(s: InmotionI2State): WheelSupport {
        val model = s.carType?.let { InmotionModelRegistry.find(it) }
            ?: return WheelSupport.FAMILY_ONLY
        if (!firmwareVerified(s)) {
            val v11yAwaitingFirmware = model.key == "v11y"
            return WheelSupport(
                identityEvidence = WheelIdentityEvidence.MODEL_ONLY,
                telemetryCoverage = if (v11yAwaitingFirmware) {
                    WheelTelemetryCoverage.NONE
                } else {
                    WheelTelemetryCoverage.CORE_ELECTRICAL
                },
                controlContract = WheelControlContract.NONE,
                reason = "firmware-unknown",
                detail = if (v11yAwaitingFirmware) {
                    "V11Y recognised, but firmware is unknown: realtime is withheld " +
                        "because the /50 layout cannot be distinguished from the core scale."
                } else {
                    "Model ${model.key} recognised, but firmware is unknown; " +
                        "controls are disabled and telemetry is core-only."
                },
            )
        }
        val coverage = when {
            model.key == "v11" && isV11EarlyLayout(s) -> WheelTelemetryCoverage.MODEL_LAYOUT
            model.key == "v11y" -> WheelTelemetryCoverage.MODEL_LAYOUT
            model.key == "v11" -> WheelTelemetryCoverage.CORE_AND_FAULTS
            else -> WheelTelemetryCoverage.CORE_ELECTRICAL
        }
        val detail = when {
            model.key == "v11" && isV11EarlyLayout(s) ->
                "V11 firmware < 1.4 early layout; phase current remains unavailable."
            model.key == "v11y" ->
                "V11Y vendor-static 74-byte layout: voltage, current, speed and battery. " +
                    "Trip and temperatures are not published (scale not established)."
            model.key == "v11" ->
                "V11 firmware >= 1.4: core fields and the established fault bitmap; " +
                    "the early telemetry layout is withheld."
            else ->
                "Model ${model.key} recognised; model-specific telemetry offsets are not " +
                    "established, so only core voltage/current are published."
        }
        val controlsClosed = controlsAllowed(s)
        return WheelSupport(
            identityEvidence = WheelIdentityEvidence.MODEL_AND_FIRMWARE,
            telemetryCoverage = coverage,
            controlContract = if (controlsClosed) {
                WheelControlContract.FAMILY_CLOSED
            } else {
                WheelControlContract.NONE
            },
            reason = when {
                !controlsClosed -> "control-contract-not-closed"
                coverage == WheelTelemetryCoverage.CORE_ELECTRICAL -> "layout-unestablished"
                coverage == WheelTelemetryCoverage.CORE_AND_FAULTS -> "layout-partial"
                else -> null
            },
            detail = if (!controlsClosed) {
                "Model ${model.key} is catalogued but its I2 command contract is not " +
                    "closed for this generation/function set; controls are disabled."
            } else {
                detail
            },
            // Only the V11 early layout is complete for the fields it claims.
            // V11Y is explicitly partial, and V11 >= 1.4 / other models carry
            // core-only or core-plus-faults coverage.
            telemetryComplete = model.key == "v11" && isV11EarlyLayout(s),
        )
    }

    private fun maybeEmitFaultChange(s: InmotionI2State, now: Long): DecodeEvent.Alert? {
        // faults is nullable: null means "no fault report yet", which is
        // not a fault-set change and must not wipe previousFaults.
        val current = s.last.faults ?: return null
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
        /**
         * Models whose I2 command frames are actually established. Catalog
         * membership alone is not evidence: V5/V8/V10 are EZCAN/I1 entries,
         * and other Lorin model rows have no closed I2 command contract yet.
         * V11 >= 1.4 uses the newer 0x50 headlight subcommand; V12+ are the
         * "and newer" path documented in [InmotionI2CommandBuilder].
         */
        private val CLOSED_CONTROL_MODELS = setOf(
            // V11Y has a vendor-static telemetry layout, but no closed write
            // contract of its own; keep it read-only until commands are
            // evidenced for that generation.
            "v11", "v12", "v12pro", "v12s", "v13", "v13pro", "v14",
        )

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

        /** No control is closed for an unknown model or an unknown firmware. */
        val READ_ONLY_CAPABILITIES: WheelCapabilities = WheelCapabilities(
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
