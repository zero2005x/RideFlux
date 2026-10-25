/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of RideFlux. It is licensed under the GNU General
 * Public License, version 3 or (at your option) any later version.
 * See the LICENSE file in the repository root for the full text.
 */
package com.rideflux.protocol.familyvesc

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.domain.codec.WheelCodec
import com.rideflux.domain.command.WheelCommand
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelIdentity

/**
 * Read-only [WheelCodec] adaptor for VESC-based vehicles.
 *
 * ### Scope
 * This adaptor publishes telemetry from `COMM_GET_VALUES_SETUP` (`0x2F`)
 * only. `COMM_GET_VALUES` (`0x04`), `COMM_GET_VALUES_SELECTIVE`
 * (`0x32`/`0x33`), firmware/package-info requests and every configuration
 * command are out of scope: there is no evidence for their reply layouts
 * or side effects here, so [encode] always returns an empty list and
 * [WheelCapabilities] keeps every control disabled.
 *
 * `GetPkgInfo` (`0x65`) is deliberately **not** sent. The request exists
 * in the vendor ecosystem, but no verified response layout is available
 * in this workspace; sending it would put an unverified command on the
 * wire and could be mistaken for telemetry. Identification therefore stays
 * at protocol level ([WheelIdentity.modelName] = `"VESC"`).
 *
 * ### Framing
 * VESC UART/BLE framing (`comm/packet.c`):
 * `[start][length…][payload][CRC16 hi][CRC16 lo][0x03]`.
 *  * `0x02` — one length byte (payload ≤ 255);
 *  * `0x03` — two length bytes, big-endian;
 *  * `0x04` — three length bytes, big-endian.
 * `length` counts the payload only (not CRC, not stop). CRC is
 * CRC-16/XMODEM over the payload, big-endian on the wire.
 *
 * Notifications are fragments: the NUS MTU is smaller than a 75-byte
 * setup reply, so this codec reassembles across notifications and resyncs
 * on the next plausible start byte after noise. A frame with a valid
 * start/length/stop but a bad CRC is reported as [DecodeEvent.Malformed];
 * arbitrary noise bytes are skipped without an event (they are not a frame).
 *
 * ### Field mapping
 * [VescSetupCodec] decodes the full-mask layout (70-byte payload). The
 * mapping uses only fields corroborated by the upstream handler and two
 * independent parsers recorded in
 * `euc-programme/findings/EDGE_BRAND_PROTOCOL_RECON.md` §10.6:
 * MOSFET temperature, motor current, input current, duty, RPM, speed,
 * input voltage, battery level, and the odometer.
 */
class VescWheelCodec(
    private val deviceAddress: String = "",
) : WheelCodec {

    override val family: WheelFamily = WheelFamily.VESC

    internal class VescState : WheelCodec.State {
        internal val buffer = ArrayList<Byte>(96)
        internal var identified: Boolean = false
    }

    override fun newState(): WheelCodec.State = VescState()

    /**
     * One poll at link-up. The connection's keep-alive loop repeats it at
     * [keepAlivePeriodMillis]; a single poll would give one sample only.
     */
    override fun handshakeFrames(state: WheelCodec.State): List<ByteArray> =
        listOf(VescSetupCodec.setupPoll())

    override fun keepAliveFrames(state: WheelCodec.State): List<ByteArray> =
        listOf(VescSetupCodec.setupPoll())

    override val keepAlivePeriodMillis: Long = POLL_PERIOD_MILLIS

    override fun encode(state: WheelCodec.State, command: WheelCommand): List<ByteArray> =
        emptyList()

    override fun decode(state: WheelCodec.State, bytes: ByteArray): List<DecodeEvent> {
        val s = state as? VescState
            ?: error("VescWheelCodec requires VescState, got ${state::class.simpleName}")
        if (bytes.isEmpty()) return emptyList()
        for (byte in bytes) s.buffer.add(byte)

        val events = ArrayList<DecodeEvent>()
        var progress = true
        while (progress) {
            progress = false
            val start = findStart(s.buffer)
            if (start < 0) {
                // No start byte in the whole buffer: keep nothing.
                s.buffer.clear()
                break
            }
            if (start > 0) {
                s.buffer.subList(0, start).clear()
                progress = true
                continue
            }
            val lengthWidth = when (s.buffer[0].toInt() and 0xff) {
                0x02 -> 1
                0x03 -> 2
                0x04 -> 3
                else -> 0
            }
            if (lengthWidth == 0) {
                s.buffer.removeAt(0)
                progress = true
                continue
            }
            if (s.buffer.size < 1 + lengthWidth) break
            var length = 0
            for (i in 0 until lengthWidth) {
                length = (length shl 8) or (s.buffer[1 + i].toInt() and 0xff)
            }
            val total = 1 + lengthWidth + length + 3 // header + payload + crc + stop
            if (length <= 0 || length > MAX_PAYLOAD) {
                s.buffer.removeAt(0)
                progress = true
                continue
            }
            if (s.buffer.size < total) break
            if ((s.buffer[total - 1].toInt() and 0xff) != 0x03) {
                // Not a frame after all: drop the start byte and resync.
                s.buffer.removeAt(0)
                progress = true
                continue
            }
            val frame = ByteArray(total) { s.buffer[it] }
            val payload = frame.copyOfRange(1 + lengthWidth, 1 + lengthWidth + length)
            val crcOnWire = ((frame[total - 3].toInt() and 0xff) shl 8) or
                (frame[total - 2].toInt() and 0xff)
            if (VescSetupCodec.crc16Xmodem(payload) != crcOnWire) {
                events.add(
                    DecodeEvent.Malformed(
                        reason = "VESC: CRC mismatch on ${length}-byte payload",
                        offendingBytes = frame,
                    ),
                )
                s.buffer.removeAt(0)
                progress = true
                continue
            }
            s.buffer.subList(0, total).clear()
            progress = true

            if ((payload[0].toInt() and 0xff) != COMM_GET_VALUES_SETUP) continue
            val values = VescSetupCodec.decode(frame) ?: continue
            val now = System.currentTimeMillis()
            val telemetry = WheelTelemetry(
                timestampMillis = now,
                // The wire field is m/s; this is the single SI → domain
                // (km/h) conversion for the whole VESC path.
                speedKmh = values.speedMetresPerSecond *
                    VescSetupCodec.METRES_PER_SECOND_TO_KILOMETRES_PER_HOUR,
                voltageV = values.inputVoltageV,
                currentA = values.inputCurrentA,
                batteryPercent = values.batteryPercent,
                mosTemperatureC = values.mosTemperatureC,
                totalDistanceMetres = values.odometerMetres,
            )
            if (!s.identified) {
                s.identified = true
                events.add(
                    DecodeEvent.Identified(
                        identity = WheelIdentity(
                            address = deviceAddress,
                            family = WheelFamily.VESC,
                            modelName = MODEL_NAME,
                        ),
                        capabilities = READ_ONLY_CAPABILITIES,
                    ),
                )
            }
            // Every published record is a decoded GET_VALUES_SETUP, which carries speed.
            events.add(DecodeEvent.TelemetryUpdate(telemetry, freshSpeed = true))
        }
        return events
    }

    /**
     * Index of the first plausible start byte, or -1.
     *
     * A byte only counts as a start when the length field that follows it is
     * non-zero and inside [MAX_PAYLOAD]; this keeps a stray `0x02` inside a
     * payload from resetting the parser on every frame.
     */
    private fun findStart(buffer: List<Byte>): Int {
        for (i in buffer.indices) {
            val b = buffer[i].toInt() and 0xff
            val width = when (b) {
                0x02 -> 1
                0x03 -> 2
                0x04 -> 3
                else -> continue
            }
            if (buffer.size < i + 1 + width) return i
            var length = 0
            for (j in 0 until width) {
                length = (length shl 8) or (buffer[i + 1 + j].toInt() and 0xff)
            }
            if (length in 1..MAX_PAYLOAD) return i
        }
        return -1
    }

    companion object {
        /** 250 ms: fast enough for a dashboard, one 6-byte poll per tick. */
        const val POLL_PERIOD_MILLIS: Long = 250L

        private const val COMM_GET_VALUES_SETUP = 0x2F
        private const val MAX_PAYLOAD = 512
        private const val MODEL_NAME = "VESC"

        /**
         * Nothing here has a verified write contract. The fact that the link
         * accepts a write says nothing about the controller accepting a
         * setting; until a readback exists every control stays disabled.
         */
        val READ_ONLY_CAPABILITIES = WheelCapabilities(
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
