package com.wheellog.next.data.protocol.decoder

import com.wheellog.next.domain.model.AlertFlag
import com.wheellog.next.domain.model.TelemetryState

/**
 * KingSong protocol decoder — full implementation per spec.
 *
 * Frame format: fixed 20 bytes, header AA 55, frame type at offset 16.
 * All multi-byte values use KingSong's "reverse every 2 bytes" encoding (getInt2R / getInt4R).
 *
 * Supported frame types:
 * - 0xA9: Live data (voltage, speed, trip distance, current, temperature)
 * - 0xB9: Distance / top speed / fan / charging / temperature2
 * - 0xBB: Model name (UTF-8 string)
 * - 0xB3: Serial number
 * - 0xF5: CPU load
 * - 0xF6: Speed limit
 * - 0xA4/0xB5: Alarms & max speed settings
 * - 0xF1/0xF2: BMS data (sub-packets by pNum at data[4])
 */
class KingSongDecoder : FrameDecoder {

    private var lastState = TelemetryState()

    // Extended info stored across frames
    var modelName: String = ""
        private set
    var serialNumber: String = ""
        private set
    var cpuLoad: Int = 0
        private set
    var output: Int = 0
        private set
    var speedLimit: Float = 0f
        private set
    var alarm1Speed: Float = 0f
        private set
    var alarm2Speed: Float = 0f
        private set
    var alarm3Speed: Float = 0f
        private set
    var maxSpeed: Float = 0f
        private set
    var topSpeed: Float = 0f
        private set
    var fanStatus: Int = 0
        private set
    var chargingStatus: Int = 0
        private set
    var temperature2: Float = 0f
        private set

    override fun decode(bytes: ByteArray): TelemetryState? {
        if (bytes.size < 20) return null
        if (bytes[0] != 0xAA.toByte() || bytes[1] != 0x55.toByte()) return null

        return when (bytes[16].toInt() and 0xFF) {
            0xA9 -> decodeLiveData(bytes)
            0xB9 -> decodeDistanceFrame(bytes)
            0xBB -> decodeNameFrame(bytes)
            0xB3 -> decodeSerialFrame(bytes)
            0xF5 -> decodeCpuLoadFrame(bytes)
            0xF6 -> decodeSpeedLimitFrame(bytes)
            0xA4, 0xB5 -> decodeAlarmsFrame(bytes)
            0xF1, 0xF2 -> decodeBmsFrame(bytes)
            else -> null
        }
    }

    // ---- Frame type 0xA9: Live data ----

    private fun decodeLiveData(bytes: ByteArray): TelemetryState {
        val voltage = ByteUtils.getInt2R(bytes, 2) / 100f
        val speed = ByteUtils.getInt2RSigned(bytes, 4) / 100f
        val tripDistance = ByteUtils.getInt4R(bytes, 6) / 1000f
        // Current uses LE encoding: (data[10]&0xFF) + (data[11]<<8)
        val current = ((bytes[10].toInt() and 0xFF) or (bytes[11].toInt() shl 8)).let {
            if (it >= 0x8000) it - 0x10000 else it
        } / 100f
        val temperature = ByteUtils.getInt2R(bytes, 12) / 100f

        val alerts = buildSet {
            if (temperature > 65f) add(AlertFlag.HIGH_TEMPERATURE)
            if (speed > 35f) add(AlertFlag.OVERSPEED)
        }

        lastState = lastState.copy(
            speedKmh = speed,
            voltageV = voltage,
            tripDistanceKm = tripDistance.toFloat(),
            currentA = current,
            temperatureC = temperature,
            batteryPercent = estimateBattery(voltage),
            alertFlags = alerts,
        )
        return lastState
    }

    // ---- Frame type 0xB9: Distance / Top speed / Fan / Charging / Temp2 ----

    private fun decodeDistanceFrame(bytes: ByteArray): TelemetryState {
        val totalDistance = ByteUtils.getInt4R(bytes, 2) / 1000f
        topSpeed = ByteUtils.getInt2R(bytes, 8) / 100f
        fanStatus = bytes[12].toInt() and 0xFF
        chargingStatus = bytes[13].toInt() and 0xFF
        temperature2 = ByteUtils.getInt2R(bytes, 14) / 100f
        lastState = lastState.copy(totalDistanceKm = totalDistance.toFloat())
        return lastState
    }

    // ---- Frame type 0xBB: Model name ----

    private fun decodeNameFrame(bytes: ByteArray): TelemetryState? {
        val nameBytes = bytes.sliceArray(2..15)
        val nullIdx = nameBytes.indexOf(0x00.toByte())
        modelName = if (nullIdx >= 0) {
            String(nameBytes, 0, nullIdx, Charsets.UTF_8)
        } else {
            String(nameBytes, Charsets.UTF_8)
        }.trim()
        return null // Name frame does not update telemetry
    }

    // ---- Frame type 0xB3: Serial number ----

    private fun decodeSerialFrame(bytes: ByteArray): TelemetryState? {
        // Serial spans data[2..15] + data[17..19]
        val part1 = bytes.sliceArray(2..15)
        val part2 = bytes.sliceArray(17..19)
        serialNumber = (String(part1, Charsets.UTF_8) + String(part2, Charsets.UTF_8)).trim()
        return null
    }

    // ---- Frame type 0xF5: CPU load ----

    private fun decodeCpuLoadFrame(bytes: ByteArray): TelemetryState? {
        cpuLoad = bytes[14].toInt() and 0xFF
        output = (bytes[15].toInt() and 0xFF) * 100
        return null
    }

    // ---- Frame type 0xF6: Speed limit ----

    private fun decodeSpeedLimitFrame(bytes: ByteArray): TelemetryState? {
        speedLimit = ByteUtils.getInt2R(bytes, 2) / 100f
        return null
    }

    // ---- Frame type 0xA4 / 0xB5: Alarms & max speed ----

    private fun decodeAlarmsFrame(bytes: ByteArray): TelemetryState? {
        alarm1Speed = ByteUtils.getInt2R(bytes, 4) / 100f
        alarm2Speed = ByteUtils.getInt2R(bytes, 6) / 100f
        alarm3Speed = ByteUtils.getInt2R(bytes, 8) / 100f
        maxSpeed = ByteUtils.getInt2R(bytes, 10) / 100f
        return null
    }

    // ---- Frame type 0xF1 / 0xF2: BMS data ----

    private fun decodeBmsFrame(bytes: ByteArray): TelemetryState? {
        // BMS sub-packet number at data[4]
        // Could be parsed for cell voltages, temperatures, etc.
        // Returning null as BMS data doesn't directly update TelemetryState
        return null
    }

    // ---- Battery estimation with voltage-group tables per spec ----

    /**
     * Estimate battery percentage based on voltage using KingSong voltage groups.
     * Groups: 67.2V, 84V, 100.8V, 126V, 151.2V, 176.4V.
     */
    internal fun estimateBattery(voltage: Float): Int {
        data class VoltageGroup(val maxV: Float, val minV: Float)

        val groups = listOf(
            VoltageGroup(67.2f, 48f),
            VoltageGroup(84f, 60f),
            VoltageGroup(100.8f, 72f),
            VoltageGroup(126f, 90f),
            VoltageGroup(151.2f, 108f),
            VoltageGroup(176.4f, 126f),
        )

        // Find the matching group: pick the smallest maxV that is >= voltage
        val group = groups.firstOrNull { voltage <= it.maxV + 2f }
            ?: groups.last()

        return ((voltage - group.minV) / (group.maxV - group.minV) * 100)
            .toInt().coerceIn(0, 100)
    }

    companion object {
        // ---- Command builder: all KingSong commands are 20-byte frames ----

        private fun buildCommand(type: Int, payload: ByteArray = ByteArray(14)): ByteArray {
            val cmd = ByteArray(20)
            cmd[0] = 0xAA.toByte()
            cmd[1] = 0x55.toByte()
            payload.copyInto(cmd, destinationOffset = 2, endIndex = minOf(payload.size, 14))
            cmd[16] = type.toByte()
            cmd[17] = 0x14.toByte()
            cmd[18] = 0x5A.toByte()
            cmd[19] = 0x5A.toByte()
            return cmd
        }

        /** Request wheel model name (response: type 0xBB). */
        fun requestName(): ByteArray = buildCommand(0x9B)

        /** Request serial number (response: type 0xB3). */
        fun requestSerial(): ByteArray = buildCommand(0x63)

        /** Request alarm settings (response: type 0xA4/0xB5). */
        fun requestAlarms(): ByteArray = buildCommand(0x98)

        /**
         * Set pedals mode: 0=hard, 1=medium, 2=soft.
         */
        fun setPedalsMode(mode: Int): ByteArray {
            val payload = ByteArray(14)
            payload[0] = mode.toByte()
            payload[1] = 0xE0.toByte()
            val cmd = buildCommand(0x87, payload)
            cmd[17] = 0x15.toByte() // setPedalsMode uses 0x15
            return cmd
        }

        /** Trigger calibration. */
        fun calibration(): ByteArray = buildCommand(0x89)

        /**
         * Set light mode. Mode values are offset by 0x12.
         */
        fun setLight(lightMode: Int): ByteArray {
            val payload = ByteArray(14)
            payload[0] = (lightMode + 0x12).toByte()
            payload[1] = 0x01.toByte()
            return buildCommand(0x73, payload)
        }

        /** Trigger beep sound. */
        fun beep(): ByteArray = buildCommand(0x88)

        /**
         * Set alarm speed thresholds and max speed (values in km/h * 100).
         */
        fun setAlarms(alarm1: Int, alarm2: Int, alarm3: Int, max: Int): ByteArray {
            val payload = ByteArray(14)
            payload[0] = (alarm1 and 0xFF).toByte()
            payload[1] = ((alarm1 shr 8) and 0xFF).toByte()
            payload[2] = (alarm2 and 0xFF).toByte()
            payload[3] = ((alarm2 shr 8) and 0xFF).toByte()
            payload[4] = (alarm3 and 0xFF).toByte()
            payload[5] = ((alarm3 shr 8) and 0xFF).toByte()
            payload[6] = (max and 0xFF).toByte()
            payload[7] = ((max shr 8) and 0xFF).toByte()
            return buildCommand(0x85, payload)
        }

        /** Power off the wheel. */
        fun powerOff(): ByteArray = buildCommand(0x40)

        /** Set LED mode. */
        fun setLedMode(mode: Int): ByteArray {
            val payload = ByteArray(14)
            payload[0] = mode.toByte()
            return buildCommand(0x6C, payload)
        }

        /** Set strobe/flash mode. */
        fun setStrobeMode(mode: Int): ByteArray {
            val payload = ByteArray(14)
            payload[0] = mode.toByte()
            return buildCommand(0x53, payload)
        }
    }
}

// Legacy top-level helpers — kept for backward compatibility with existing tests
internal fun readUInt16BE(bytes: ByteArray, offset: Int): Int = ByteUtils.readUInt16BE(bytes, offset)
internal fun readInt16BE(bytes: ByteArray, offset: Int): Int = ByteUtils.readInt16BE(bytes, offset)
internal fun readUInt32BE(bytes: ByteArray, offset: Int): Long = ByteUtils.readUInt32BE(bytes, offset)
