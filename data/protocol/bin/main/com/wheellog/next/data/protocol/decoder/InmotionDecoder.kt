package com.wheellog.next.data.protocol.decoder

import com.wheellog.next.domain.model.AlertFlag
import com.wheellog.next.domain.model.TelemetryState

/**
 * Inmotion v1 protocol decoder — full implementation per spec.
 *
 * Uses FFE0/FFE4 (notify) and FFE5/FFE9 (write).
 * Outer frame: header AA AA, footer 55 55, with A5 escape sequences.
 * Inner structure: 16-byte CANMessage header + optional ex_data.
 * Checksum: unsigned byte sum & 0xFF (8-bit).
 *
 * Supported IDValues:
 * - GetFastInfo (0x0F550113): real-time telemetry from ex_data
 * - GetSlowInfo (0x0F550114): model/serial/firmware info
 * - Alert (0x0F780101): alert events
 *
 * The decoder uses an InmotionUnpacker state machine to handle stream
 * reassembly with escape processing.
 * Write commands must be split into 20-byte chunks with 20ms inter-chunk delay.
 */
class InmotionDecoder : FrameDecoder {

    private var lastState = TelemetryState()
    private val unpacker = InmotionUnpacker()

    // Extended info from SlowInfo
    var modelName: String = ""
        private set
    var serialNumber: String = ""
        private set
    var firmware: String = ""
        private set
    var speedCalculationFactor: Float = 1.0f
        private set

    override fun decode(bytes: ByteArray): TelemetryState? {
        var result: TelemetryState? = null
        for (b in bytes) {
            val frame = unpacker.feed(b)
            if (frame != null) {
                val parsed = processFrame(frame)
                if (parsed != null) result = parsed
            }
        }
        return result
    }

    /**
     * Process a complete unescaped frame (between AA AA and 55 55).
     */
    internal fun processFrame(frame: ByteArray): TelemetryState? {
        if (frame.size < 17) return null // 16-byte header + at least 1 checksum byte

        // Verify checksum: last byte should equal SUM(all preceding bytes) & 0xFF
        val checkByte = frame[frame.size - 1].toInt() and 0xFF
        val computed = computeChecksum(frame, 0, frame.size - 1)
        if (checkByte != computed) return null

        // Parse CANMessage header (16 bytes)
        val id = ByteUtils.readUInt32LE(frame, 0)
        val canData = frame.sliceArray(4 until 12)
        val len = frame[12].toInt() and 0xFF

        // Extended data area (if len == 0xFE, extended mode)
        val exData: ByteArray? = if (len == 0xFE && frame.size > 18) {
            // Extended total = lenEx + 21 (calculated from bytes after header)
            frame.sliceArray(16 until frame.size - 1) // ex_data before checksum
        } else if (frame.size > 16) {
            null
        } else {
            null
        }

        return when (id) {
            IDValue.GET_FAST_INFO -> parseFastInfo(exData ?: canData)
            IDValue.GET_SLOW_INFO -> {
                parseSlowInfo(exData ?: canData)
                null
            }
            IDValue.ALERT -> {
                parseAlert(canData)
                null
            }
            else -> null
        }
    }

    // ---- ParseFastInfoMessage: real-time telemetry from ex_data ----

    private fun parseFastInfo(data: ByteArray): TelemetryState? {
        if (data.size < 52) return null

        val speed1 = ByteUtils.readInt32LE(data, 12)
        val speed2 = ByteUtils.readInt32LE(data, 16)
        val speed = if (speedCalculationFactor != 0f) {
            (speed1 + speed2) / (speedCalculationFactor * 2) / 100f
        } else {
            (speed1 + speed2) / 200f
        }

        val current = ByteUtils.readInt32LE(data, 20) / 100f
        val voltage = ByteUtils.readUInt32LE(data, 24).toFloat() / 100f
        val temperature = (data[32].toInt() and 0xFF).toFloat()
        val totalDistance = ByteUtils.readUInt32LE(data, 44).toFloat() / 1000f
        val tripDistance = ByteUtils.readUInt32LE(data, 48).toFloat() / 1000f

        val battery = estimateBattery(voltage)
        val alerts = buildSet {
            if (temperature > 65f) add(AlertFlag.HIGH_TEMPERATURE)
            if (battery < 10) add(AlertFlag.LOW_BATTERY)
            if (kotlin.math.abs(speed) > 35f) add(AlertFlag.OVERSPEED)
        }

        lastState = TelemetryState(
            speedKmh = speed,
            voltageV = voltage,
            currentA = current,
            temperatureC = temperature,
            totalDistanceKm = totalDistance,
            tripDistanceKm = tripDistance,
            batteryPercent = battery,
            alertFlags = alerts,
        )
        return lastState
    }

    // ---- ParseSlowInfoMessage: model, serial, firmware ----

    private fun parseSlowInfo(data: ByteArray) {
        if (data.size < 110) return
        // Model identification from bytes 104/107
        // Serial from bytes 0-7 (reversed hex string)
        if (data.size >= 8) {
            val serialBytes = data.sliceArray(0 until 8)
            serialNumber = serialBytes.joinToString("") { "%02X".format(it) }
        }
        // Firmware version from bytes 24-27
        if (data.size >= 28) {
            firmware = "${data[24]}.${data[25]}.${data[26]}.${data[27]}"
        }
    }

    // ---- ParseAlertInfoMessage ----

    private fun parseAlert(data: ByteArray) {
        if (data.size < 4) return
        val alertId = data[0].toInt() and 0xFF
        // Known alerts: 0x05=high temp, 0x06=overspeed, 0x19=low battery
        val alerts = buildSet {
            addAll(lastState.alertFlags)
            when (alertId) {
                0x05 -> add(AlertFlag.HIGH_TEMPERATURE)
                0x06 -> add(AlertFlag.OVERSPEED)
                0x19 -> add(AlertFlag.LOW_BATTERY)
            }
        }
        lastState = lastState.copy(alertFlags = alerts)
    }

    private fun estimateBattery(voltage: Float): Int {
        // Typical Inmotion v1 packs are 84V nominal
        val minV = 60f
        val maxV = 84f
        return ((voltage - minV) / (maxV - minV) * 100).toInt().coerceIn(0, 100)
    }

    companion object {
        /** Compute Inmotion v1 checksum: unsigned byte sum & 0xFF. */
        fun computeChecksum(data: ByteArray, start: Int = 0, end: Int = data.size): Int {
            var sum = 0
            for (i in start until end) {
                sum += (data[i].toInt() and 0xFF)
            }
            return sum and 0xFF
        }

        /**
         * Split a command into 20-byte chunks for Inmotion v1 BLE write.
         * Each chunk should be written with ~20ms delay between them.
         */
        fun chunkCommand(cmd: ByteArray): List<ByteArray> {
            val chunks = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < cmd.size) {
                val chunkSize = minOf(20, cmd.size - offset)
                chunks.add(cmd.sliceArray(offset until offset + chunkSize))
                offset += chunkSize
            }
            return chunks
        }

        /**
         * Apply A5 escape encoding to raw data for transmission.
         * Wraps with AA AA header and 55 55 footer.
         */
        fun encodeFrame(payload: ByteArray): ByteArray {
            val escaped = mutableListOf<Byte>()
            escaped.add(0xAA.toByte())
            escaped.add(0xAA.toByte())
            for (b in payload) {
                when (b) {
                    0xAA.toByte() -> { escaped.add(0xA5.toByte()); escaped.add(0xAA.toByte()) }
                    0x55.toByte() -> { escaped.add(0xA5.toByte()); escaped.add(0x55.toByte()) }
                    0xA5.toByte() -> { escaped.add(0xA5.toByte()); escaped.add(0xA5.toByte()) }
                    else -> escaped.add(b)
                }
            }
            escaped.add(0x55.toByte())
            escaped.add(0x55.toByte())
            return escaped.toByteArray()
        }
    }
}

// ---- IDValue constants (Inmotion v1 CAN message IDs) ----

object IDValue {
    const val GET_FAST_INFO = 0x0F550113L
    const val GET_SLOW_INFO = 0x0F550114L
    const val RIDE_MODE = 0x0F550115L
    const val REMOTE_CONTROL = 0x0F550116L
    const val CALIBRATION = 0x0F550119L
    const val PIN_CODE = 0x0F550307L
    const val LIGHT = 0x0F55010DL
    const val HANDLE_BUTTON = 0x0F55012EL
    const val SPEAKER_VOLUME = 0x0F55060AL
    const val PLAY_SOUND = 0x0F550609L
    const val ALERT = 0x0F780101L
}

// ---- InmotionUnpacker: stream-based with AA AA / 55 55 framing and A5 escape ----

/**
 * State machine for Inmotion v1 frame reassembly.
 * Collects bytes between AA AA (start) and 55 55 (end),
 * handling A5 escape sequences.
 */
class InmotionUnpacker {

    private enum class State { UNKNOWN, HEADER1, STARTED, ESCAPE, FOOTER1 }

    private var state = State.UNKNOWN
    private var buffer = mutableListOf<Byte>()

    /**
     * Feed a single byte. Returns a complete unescaped frame when 55 55 footer is found.
     */
    fun feed(b: Byte): ByteArray? {
        return when (state) {
            State.UNKNOWN -> {
                if (b == 0xAA.toByte()) {
                    state = State.HEADER1
                }
                null
            }
            State.HEADER1 -> {
                if (b == 0xAA.toByte()) {
                    state = State.STARTED
                    buffer.clear()
                } else {
                    state = State.UNKNOWN
                }
                null
            }
            State.STARTED -> {
                when (b) {
                    0xA5.toByte() -> {
                        state = State.ESCAPE
                        null
                    }
                    0x55.toByte() -> {
                        state = State.FOOTER1
                        null
                    }
                    else -> {
                        buffer.add(b)
                        null
                    }
                }
            }
            State.ESCAPE -> {
                // Next byte is the actual value (reversed escape)
                buffer.add(b)
                state = State.STARTED
                null
            }
            State.FOOTER1 -> {
                if (b == 0x55.toByte()) {
                    // Complete frame found
                    val frame = buffer.toByteArray()
                    state = State.UNKNOWN
                    buffer.clear()
                    frame
                } else {
                    // False footer - the 0x55 was data
                    buffer.add(0x55.toByte())
                    buffer.add(b)
                    state = State.STARTED
                    null
                }
            }
        }
    }

    fun reset() {
        state = State.UNKNOWN
        buffer.clear()
    }
}
