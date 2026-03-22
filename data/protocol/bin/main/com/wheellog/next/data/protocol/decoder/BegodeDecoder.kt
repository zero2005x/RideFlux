package com.wheellog.next.data.protocol.decoder

import com.wheellog.next.domain.model.AlertFlag
import com.wheellog.next.domain.model.TelemetryState

/**
 * Begode / Gotway protocol decoder — full implementation per spec.
 *
 * Frame format: fixed 24 bytes, header 55 AA (data[0..1]),
 * footer 5A 5A 5A 5A (data[20..23]). No checksum.
 * Byte order: Big-Endian.
 *
 * Frame type determined by data[18]:
 * - 0x00: Frame A — real-time data (voltage/speed/current/temperature/distance)
 * - 0x04: Frame B — total distance / settings / LED
 * - 0x01: BMS overview (voltage/current/temperatures)
 * - 0x02: BMS cells 1 (cell voltages 0-7)
 * - 0x03: BMS cells 2 (cell voltages 8-15)
 * - 0x07: Extended (battery current / motor temp / PWM)
 *
 * Speed formula: raw is BE signed, unit = (km/h × 100 ÷ 3.6), so km/h = raw × 3.6 / 100.
 * Temperature: MPU6050 formula — (data >> 8) + 80 - 256.
 * Commands are written as ASCII strings.
 */
class BegodeDecoder : FrameDecoder {

    private var lastState = TelemetryState()

    /**
     * Frame accumulation buffer.
     * Begode frames are 24 bytes but BLE notifications may deliver smaller
     * chunks (typically 20 bytes due to default BLE MTU). This buffer
     * accumulates incoming bytes until a complete frame can be extracted.
     */
    private val buffer = mutableListOf<Byte>()

    // Extended info from non-telemetry frames
    var totalDistance: Float = 0f
        private set
    var ledMode: Int = 0
        private set
    var lightMode: Int = 0
        private set
    var tiltBackSpeed: Int = 0
        private set
    var bmsVoltage: Float = 0f
        private set
    var bmsCurrent: Float = 0f
        private set
    var motorTemp: Float = 0f
        private set
    var cellVoltages: FloatArray = FloatArray(16)
        private set

    override fun decode(bytes: ByteArray): TelemetryState? {
        buffer.addAll(bytes.toList())

        // Prevent unbounded growth from noisy / corrupt data
        if (buffer.size > MAX_BUFFER_SIZE) {
            buffer.clear()
            return null
        }

        // Scan for a valid frame inside the buffer
        return extractFrame()
    }

    /**
     * Try to find and consume a complete 24-byte frame from the buffer.
     * Scans for the 55 AA header, then checks if 24 bytes are available
     * and if the footer 5A 5A 5A 5A is present.
     */
    private fun extractFrame(): TelemetryState? {
        while (buffer.size >= FRAME_LENGTH) {
            // Find header 55 AA
            val headerIdx = findHeader()
            if (headerIdx < 0) {
                buffer.clear()
                return null
            }

            // Discard bytes before the header
            if (headerIdx > 0) {
                buffer.subList(0, headerIdx).clear()
            }

            // Wait for more data if buffer is still too short
            if (buffer.size < FRAME_LENGTH) return null

            val frame = ByteArray(FRAME_LENGTH) { buffer[it] }

            // Validate footer: 5A 5A 5A 5A at offsets 20-23
            if (frame[20] == 0x5A.toByte() && frame[21] == 0x5A.toByte()
                && frame[22] == 0x5A.toByte() && frame[23] == 0x5A.toByte()
            ) {
                // Valid frame — consume it from the buffer and decode
                buffer.subList(0, FRAME_LENGTH).clear()
                return decodeFrame(frame)
            }

            // Invalid footer — skip this 55 AA as false header, advance by 1
            buffer.removeAt(0)
        }
        return null
    }

    /** Find the first position of the 55 AA header in the buffer. */
    private fun findHeader(): Int {
        for (i in 0..buffer.size - 2) {
            if (buffer[i] == 0x55.toByte() && buffer[i + 1] == 0xAA.toByte()) {
                return i
            }
        }
        return -1
    }

    /** Dispatch a validated 24-byte frame to the appropriate sub-decoder. */
    private fun decodeFrame(bytes: ByteArray): TelemetryState? {
        val frameType = bytes[18].toInt() and 0xFF
        return when (frameType) {
            0x00 -> decodeFrameA(bytes)
            0x04 -> decodeFrameB(bytes)
            0x01 -> decodeBmsOverview(bytes)
            0x02 -> decodeBmsCells(bytes, 0)
            0x03 -> decodeBmsCells(bytes, 8)
            0x07 -> decodeExtended(bytes)
            else -> null
        }
    }

    // ---- Frame A (data[18] = 0x00): real-time data ----

    private fun decodeFrameA(bytes: ByteArray): TelemetryState {
        val voltage = ByteUtils.readUInt16BE(bytes, 2) / 100f
        val speed = ByteUtils.readInt16BE(bytes, 4) * 3.6f / 100f
        val distance = ByteUtils.readUInt32BE(bytes, 8).toFloat()
        val phaseCurrent = ByteUtils.readInt16BE(bytes, 10) / 100f
        // Temperature: MPU6050 formula — high byte unsigned + 80 - 256
        val rawTemp = ByteUtils.readUInt16BE(bytes, 12)
        val temperature = ((rawTemp shr 8) + 80 - 256).toFloat()
        val hwPwm = ByteUtils.readUInt16BE(bytes, 14)

        val battery = estimateBattery(voltage)

        val alerts = buildSet {
            if (temperature > 65f) add(AlertFlag.HIGH_TEMPERATURE)
            if (battery < 10) add(AlertFlag.LOW_BATTERY)
            if (kotlin.math.abs(speed) > 35f) add(AlertFlag.OVERSPEED)
        }

        lastState = TelemetryState(
            speedKmh = speed,
            batteryPercent = battery,
            voltageV = voltage,
            temperatureC = temperature,
            totalDistanceKm = totalDistance,
            tripDistanceKm = distance / 1000f,
            currentA = phaseCurrent,
            alertFlags = alerts,
        )
        return lastState
    }

    // ---- Frame B (data[18] = 0x04): total distance / settings ----

    private fun decodeFrameB(bytes: ByteArray): TelemetryState {
        totalDistance = ByteUtils.readUInt32BE(bytes, 2).toFloat() / 1000f
        tiltBackSpeed = ByteUtils.readUInt16BE(bytes, 10)
        ledMode = bytes[13].toInt() and 0xFF
        lightMode = bytes[15].toInt() and 0xFF

        lastState = lastState.copy(totalDistanceKm = totalDistance)
        return lastState
    }

    // ---- Frame 0x01: BMS overview ----

    private fun decodeBmsOverview(bytes: ByteArray): TelemetryState? {
        bmsVoltage = ByteUtils.readUInt16BE(bytes, 6) / 10f
        bmsCurrent = ByteUtils.readInt16BE(bytes, 8).toFloat()
        return null
    }

    // ---- Frame 0x02 / 0x03: BMS cell voltages ----

    private fun decodeBmsCells(bytes: ByteArray, startIdx: Int): TelemetryState? {
        for (i in 0 until 8) {
            val offset = 2 + i * 2
            if (offset + 1 < 20) {
                cellVoltages[startIdx + i] = ByteUtils.readUInt16BE(bytes, offset) / 1000f
            }
        }
        return null
    }

    // ---- Frame 0x07: Extended ----

    private fun decodeExtended(bytes: ByteArray): TelemetryState? {
        // batteryCurrent@2, motorTemp@6, hwPWM@8
        motorTemp = ByteUtils.readInt16BE(bytes, 6).toFloat()
        return null
    }

    // ---- Battery estimation — auto-detects cell group from voltage ----

    internal fun estimateBattery(voltage: Float): Int {
        data class VoltageGroup(val maxV: Float, val minV: Float)
        val groups = listOf(
            VoltageGroup(67.2f, 48f),    // 16S
            VoltageGroup(84f, 60f),      // 20S
            VoltageGroup(100.8f, 72f),   // 24S
            VoltageGroup(117.6f, 84f),   // 28S
            VoltageGroup(126f, 90f),     // 30S
            VoltageGroup(134.4f, 96f),   // 32S
        )
        val group = groups.firstOrNull { voltage <= it.maxV + 2f } ?: groups.last()
        return ((voltage - group.minV) / (group.maxV - group.minV) * 100)
            .toInt().coerceIn(0, 100)
    }

    companion object {
        /** Complete Begode frame length including header + footer. */
        private const val FRAME_LENGTH = 24
        /** Maximum buffer size to prevent unbounded growth from corrupt data. */
        private const val MAX_BUFFER_SIZE = 512

        // ---- Command builders: Begode uses ASCII strings ----

        /** Set pedals mode: hard. */
        fun pedalHard(): ByteArray = "h".toByteArray(Charsets.US_ASCII)

        /** Set pedals mode: medium. */
        fun pedalMedium(): ByteArray = "f".toByteArray(Charsets.US_ASCII)

        /** Set pedals mode: soft. */
        fun pedalSoft(): ByteArray = "s".toByteArray(Charsets.US_ASCII)

        /** Set pedals mode: comfort. */
        fun pedalComfort(): ByteArray = "i".toByteArray(Charsets.US_ASCII)

        /** Light off. */
        fun lightOff(): ByteArray = "E".toByteArray(Charsets.US_ASCII)

        /** Light on. */
        fun lightOn(): ByteArray = "Q".toByteArray(Charsets.US_ASCII)

        /** Light strobe. */
        fun lightStrobe(): ByteArray = "T".toByteArray(Charsets.US_ASCII)

        /** Short beep. */
        fun beep(): ByteArray = "b".toByteArray(Charsets.US_ASCII)

        /** Read firmware version. */
        fun readVersion(): ByteArray = "V".toByteArray(Charsets.US_ASCII)

        /** Read model name. */
        fun readName(): ByteArray = "N".toByteArray(Charsets.US_ASCII)

        /** Two-step calibration: send "c", wait 300ms, then send "y". */
        fun calibrationStep1(): ByteArray = "c".toByteArray(Charsets.US_ASCII)
        fun calibrationStep2(): ByteArray = "y".toByteArray(Charsets.US_ASCII)
    }
}
