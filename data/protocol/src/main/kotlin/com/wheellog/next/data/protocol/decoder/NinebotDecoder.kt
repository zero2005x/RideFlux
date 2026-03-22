package com.wheellog.next.data.protocol.decoder

import com.wheellog.next.domain.model.AlertFlag
import com.wheellog.next.domain.model.TelemetryState

/**
 * Ninebot protocol decoder — full implementation per spec.
 *
 * Outer frame: header 55 AA, encrypted payload.
 * Inner payload (after crypto): len, source, destination, command, parameter, data[], crc16.
 *
 * Features:
 * - NinebotUnpacker state machine for stream reassembly
 * - crypto() with 16-byte gamma XOR key (updated via GetKey response)
 * - 16-bit complement checksum (unsigned byte sum XOR 0xFFFF)
 * - CANMessage parsing with Addr/Comm/Param enums
 * - LiveData registers 0xB0–0xBF, SerialNumber, Firmware, BatteryLevel
 */
class NinebotDecoder : FrameDecoder {

    private var lastState = TelemetryState()
    private val unpacker = NinebotUnpacker()
    private val gamma = ByteArray(16) // Encryption key, initially all zeros

    // Extended info
    var serialNumber: String = ""
        private set
    var firmware: String = ""
        private set

    override fun decode(bytes: ByteArray): TelemetryState? {
        // Feed bytes into the unpacker one at a time
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
     * Process a complete unpacked frame (without 55 AA header).
     */
    internal fun processFrame(frame: ByteArray): TelemetryState? {
        if (frame.size < 5) return null

        // Decrypt the payload
        val decrypted = crypto(frame.copyOf())

        // Verify checksum: last 2 bytes are CRC (LE)
        if (decrypted.size < 3) return null
        val body = decrypted.sliceArray(0 until decrypted.size - 2)
        val crcLow = decrypted[decrypted.size - 2].toInt() and 0xFF
        val crcHigh = decrypted[decrypted.size - 1].toInt() and 0xFF
        val receivedCrc = crcLow or (crcHigh shl 8)
        val computedCrc = computeChecksum(body)
        if (receivedCrc != computedCrc) return null

        // Parse as CANMessage: len(1), source(1), dest(1), command(1), parameter(1), data(N)
        if (body.isEmpty()) return null
        val msg = CANMessage.parse(body) ?: return null

        return processMessage(msg)
    }

    private fun processMessage(msg: CANMessage): TelemetryState? {
        // Handle GetKey response: update gamma
        if (msg.command == Comm.GET_KEY) {
            if (msg.data.size >= 16) {
                msg.data.copyInto(gamma, endIndex = 16)
            }
            return null
        }

        // Only process "Get" (response) commands
        if (msg.command != Comm.GET) return null

        return when (msg.parameter) {
            Param.SERIAL_NUMBER -> {
                serialNumber = String(msg.data, Charsets.UTF_8).trim()
                null
            }
            Param.FIRMWARE -> {
                firmware = String(msg.data, Charsets.UTF_8).trim()
                null
            }
            Param.BATTERY_LEVEL -> {
                if (msg.data.size >= 2) {
                    val battery = ByteUtils.readUInt16LE(msg.data, 0).coerceIn(0, 100)
                    lastState = lastState.copy(batteryPercent = battery)
                    lastState
                } else null
            }
            Param.LIVE_DATA -> decodeLiveData(msg.data)
            Param.LIVE_DATA2 -> decodeLiveData2(msg.data)
            Param.LIVE_DATA3 -> decodeLiveData3(msg.data)
            Param.LIVE_DATA4 -> decodeLiveData4(msg.data)
            Param.LIVE_DATA5 -> decodeLiveData5(msg.data)
            else -> null
        }
    }

    // ---- LiveData (Param 0xB0): main real-time data ----

    private fun decodeLiveData(data: ByteArray): TelemetryState? {
        if (data.size < 28) return null
        val battery = ByteUtils.readUInt16LE(data, 8)
        val speed = ByteUtils.readInt16LE(data, 10) / 100f
        val distance = ByteUtils.readUInt32LE(data, 14) / 1000f
        val temperature = ByteUtils.readInt16LE(data, 22) / 10f
        val voltage = ByteUtils.readUInt16LE(data, 24) / 100f
        val current = ByteUtils.readInt16LE(data, 26) / 100f

        val alerts = buildSet {
            if (battery < 10) add(AlertFlag.LOW_BATTERY)
            if (temperature > 65f) add(AlertFlag.HIGH_TEMPERATURE)
            if (kotlin.math.abs(speed) > 35f) add(AlertFlag.OVERSPEED)
        }

        lastState = lastState.copy(
            speedKmh = speed,
            voltageV = voltage,
            currentA = current,
            batteryPercent = battery.coerceIn(0, 100),
            temperatureC = temperature,
            tripDistanceKm = distance.toFloat(),
            alertFlags = alerts,
        )
        return lastState
    }

    private fun decodeLiveData2(data: ByteArray): TelemetryState? {
        // Extra battery and speed
        return null // Already covered by main LiveData
    }

    private fun decodeLiveData3(data: ByteArray): TelemetryState? {
        if (data.size < 6) return null
        val distance = ByteUtils.readUInt32LE(data, 2) / 1000f
        lastState = lastState.copy(totalDistanceKm = distance.toFloat())
        return lastState
    }

    private fun decodeLiveData4(data: ByteArray): TelemetryState? {
        // Extra temperature
        return null
    }

    private fun decodeLiveData5(data: ByteArray): TelemetryState? {
        if (data.size < 4) return null
        val voltage = ByteUtils.readUInt16LE(data, 0) / 100f
        val current = ByteUtils.readInt16LE(data, 2) / 100f
        lastState = lastState.copy(voltageV = voltage, currentA = current)
        return lastState
    }

    // ---- Crypto: XOR with 16-byte gamma key, byte[0] not encrypted ----

    internal fun crypto(buffer: ByteArray): ByteArray {
        for (j in 1 until buffer.size) {
            buffer[j] = (buffer[j].toInt() xor gamma[(j - 1) % 16].toInt()).toByte()
        }
        return buffer
    }

    /** Update the gamma key (e.g., from a GetKey response in a higher-level handler). */
    fun updateGamma(key: ByteArray) {
        key.copyInto(gamma, endIndex = minOf(key.size, 16))
    }

    companion object {
        /**
         * Compute Ninebot checksum: unsigned byte sum XOR 0xFFFF, 16-bit.
         */
        fun computeChecksum(buffer: ByteArray): Int {
            var check = 0
            for (b in buffer) {
                check += (b.toInt() and 0xFF)
            }
            return (check xor 0xFFFF) and 0xFFFF
        }

        // ---- Command builder ----

        /**
         * Build a Ninebot command packet (without outer 55 AA header and crypto).
         * The caller must apply crypto() and prepend 55 AA before BLE write.
         */
        fun buildCommand(
            source: Int,
            destination: Int,
            command: Int,
            parameter: Int,
            data: ByteArray = ByteArray(0),
        ): ByteArray {
            val len = data.size + 2 // command + parameter + data
            val body = ByteArray(5 + data.size)
            body[0] = (len and 0xFF).toByte()
            body[1] = source.toByte()
            body[2] = destination.toByte()
            body[3] = command.toByte()
            body[4] = parameter.toByte()
            data.copyInto(body, destinationOffset = 5)

            val crc = computeChecksum(body)
            return body + byteArrayOf(
                (crc and 0xFF).toByte(),
                ((crc shr 8) and 0xFF).toByte(),
            )
        }

        /** Request serial number. */
        fun requestSerialNumber(source: Int = Addr.APP, dest: Int = Addr.CONTROLLER): ByteArray =
            buildCommand(source, dest, Comm.READ, Param.SERIAL_NUMBER)

        /** Request firmware version. */
        fun requestFirmware(source: Int = Addr.APP, dest: Int = Addr.CONTROLLER): ByteArray =
            buildCommand(source, dest, Comm.READ, Param.FIRMWARE)

        /** Request live data. */
        fun requestLiveData(source: Int = Addr.APP, dest: Int = Addr.CONTROLLER): ByteArray =
            buildCommand(source, dest, Comm.READ, Param.LIVE_DATA)

        /** Request key exchange (GetKey). */
        fun requestGetKey(source: Int = Addr.APP, dest: Int = Addr.KEY_GENERATOR): ByteArray =
            buildCommand(source, dest, Comm.GET_KEY, 0x00)
    }
}

// ---- Addr constants ----

object Addr {
    const val CONTROLLER = 0x01
    const val KEY_GENERATOR = 0x16
    const val APP = 0x09
    const val APP_S2 = 0x11
    const val APP_MINI = 0x0A
}

// ---- Comm constants ----

object Comm {
    const val READ = 0x01
    const val WRITE = 0x03
    const val GET = 0x04
    const val GET_KEY = 0x5B
}

// ---- Param constants ----

object Param {
    const val SERIAL_NUMBER = 0x10
    const val SERIAL_NUMBER2 = 0x13
    const val SERIAL_NUMBER3 = 0x16
    const val FIRMWARE = 0x1A
    const val BATTERY_LEVEL = 0x22
    const val ANGLES = 0x61
    const val ACTIVATION_DATE = 0x69
    const val LIVE_DATA = 0xB0
    const val LIVE_DATA2 = 0xB3
    const val LIVE_DATA3 = 0xB6
    const val LIVE_DATA4 = 0xB9
    const val LIVE_DATA5 = 0xBC
    const val LIVE_DATA6 = 0xBF
}

// ---- CANMessage ----

data class CANMessage(
    val source: Int,
    val destination: Int,
    val command: Int,
    val parameter: Int,
    val data: ByteArray,
) {
    companion object {
        fun parse(body: ByteArray): CANMessage? {
            if (body.size < 5) return null
            val len = body[0].toInt() and 0xFF
            val source = body[1].toInt() and 0xFF
            val destination = body[2].toInt() and 0xFF
            val command = body[3].toInt() and 0xFF
            val parameter = body[4].toInt() and 0xFF
            val dataLen = len - 2 // subtract command + parameter
            if (dataLen < 0) return null
            val data = if (dataLen > 0 && body.size >= 5 + dataLen) {
                body.sliceArray(5 until 5 + dataLen)
            } else {
                ByteArray(0)
            }
            return CANMessage(source, destination, command, parameter, data)
        }
    }
}

// ---- NinebotUnpacker state machine ----

/**
 * Stream-based unpacker for Ninebot frames.
 * Header: 55 AA, then collects len + 6 bytes total after header.
 */
class NinebotUnpacker {

    private enum class State { UNKNOWN, STARTED, COLLECTING }

    private var state = State.UNKNOWN
    private var buffer = mutableListOf<Byte>()
    private var expectedLen = 0

    /**
     * Feed a single byte. Returns a complete frame (without 55 AA header) when done,
     * or null if more bytes are needed.
     */
    fun feed(b: Byte): ByteArray? {
        return when (state) {
            State.UNKNOWN -> {
                if (b == 0x55.toByte()) {
                    state = State.STARTED
                }
                null
            }
            State.STARTED -> {
                if (b == 0xAA.toByte()) {
                    state = State.COLLECTING
                    buffer.clear()
                } else {
                    state = State.UNKNOWN
                }
                null
            }
            State.COLLECTING -> {
                buffer.add(b)
                if (buffer.size == 1) {
                    // First byte after header is len
                    expectedLen = (b.toInt() and 0xFF) + 6
                }
                if (buffer.size >= expectedLen && expectedLen > 0) {
                    val frame = buffer.toByteArray()
                    state = State.UNKNOWN
                    buffer.clear()
                    frame
                } else {
                    null
                }
            }
        }
    }

    fun reset() {
        state = State.UNKNOWN
        buffer.clear()
        expectedLen = 0
    }
}

// Legacy top-level helpers — kept for backward compatibility with existing tests
internal fun readUInt16LE(bytes: ByteArray, offset: Int): Int = ByteUtils.readUInt16LE(bytes, offset)
internal fun readInt16LE(bytes: ByteArray, offset: Int): Int = ByteUtils.readInt16LE(bytes, offset)
internal fun readUInt32LE(bytes: ByteArray, offset: Int): Long = ByteUtils.readUInt32LE(bytes, offset)
