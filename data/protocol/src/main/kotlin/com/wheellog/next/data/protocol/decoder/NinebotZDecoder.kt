package com.wheellog.next.data.protocol.decoder

import com.wheellog.next.domain.model.AlertFlag
import com.wheellog.next.domain.model.TelemetryState

/**
 * Ninebot Z-series protocol decoder — full implementation per spec.
 *
 * Uses NUS channel (6E400001). Outer frame: header 5A A5, encrypted payload.
 * Same crypto() and checksum as Ninebot, but different Addr constants
 * and extended parameter set (LED, pedal sensitivity, speaker volume, etc.).
 *
 * Unpacker: expects 5A A5 header, frame length = len + 9 (different from base Ninebot).
 *
 * LiveData (Param 0xB0) fields:
 *  [0..1]  errorCode, [2..3] alarmCode, [4..5] escStatus,
 *  [8..9]  battery, [10..11] speed (signed), [12..13] avgSpeed,
 *  [14..17] distance (32-bit), [18..19] tripDistance (* 10),
 *  [20..21] operatingTime, [22..23] temperature (signed, / 10),
 *  [24..25] voltage (* 100), [26..27] current (signed, * 100)
 */
class NinebotZDecoder : FrameDecoder {

    private var lastState = TelemetryState()
    private val unpacker = NinebotZUnpacker()
    private val gamma = ByteArray(16)

    // Extended info
    var serialNumber: String = ""
        private set
    var firmware: String = ""
        private set
    var bleVersion: String = ""
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

    internal fun processFrame(frame: ByteArray): TelemetryState? {
        if (frame.size < 5) return null

        val decrypted = crypto(frame.copyOf())
        if (decrypted.size < 3) return null

        val body = decrypted.sliceArray(0 until decrypted.size - 2)
        val crcLow = decrypted[decrypted.size - 2].toInt() and 0xFF
        val crcHigh = decrypted[decrypted.size - 1].toInt() and 0xFF
        val receivedCrc = crcLow or (crcHigh shl 8)
        val computedCrc = NinebotDecoder.computeChecksum(body)
        if (receivedCrc != computedCrc) return null

        val msg = CANMessage.parse(body) ?: return null
        return processMessage(msg)
    }

    private fun processMessage(msg: CANMessage): TelemetryState? {
        if (msg.command == Comm.GET_KEY) {
            if (msg.data.size >= 16) {
                msg.data.copyInto(gamma, endIndex = 16)
            }
            return null
        }

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
            NinebotZParam.BLE_VERSION -> {
                bleVersion = String(msg.data, Charsets.UTF_8).trim()
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
            else -> null
        }
    }

    // ---- LiveData (Param 0xB0) for Ninebot Z ----

    private fun decodeLiveData(data: ByteArray): TelemetryState? {
        if (data.size < 28) return null

        val battery = ByteUtils.readUInt16LE(data, 8)
        val speed = ByteUtils.readInt16LE(data, 10) / 100f
        val distance = ByteUtils.readUInt32LE(data, 14) / 1000f
        val tripDistance = ByteUtils.readUInt16LE(data, 18) / 10f / 1000f // * 10 then to km
        val temperature = ByteUtils.readInt16LE(data, 22) / 10f
        val voltage = ByteUtils.readUInt16LE(data, 24) / 100f
        val current = ByteUtils.readInt16LE(data, 26) / 100f

        val alerts = buildSet {
            if (battery < 10) add(AlertFlag.LOW_BATTERY)
            if (temperature > 65f) add(AlertFlag.HIGH_TEMPERATURE)
            if (kotlin.math.abs(speed) > 35f) add(AlertFlag.OVERSPEED)
        }

        lastState = TelemetryState(
            speedKmh = speed,
            voltageV = voltage,
            currentA = current,
            batteryPercent = battery.coerceIn(0, 100),
            temperatureC = temperature,
            tripDistanceKm = tripDistance,
            totalDistanceKm = distance.toFloat(),
            alertFlags = alerts,
        )
        return lastState
    }

    // ---- Crypto: same as Ninebot — XOR with gamma, byte[0] not encrypted ----

    internal fun crypto(buffer: ByteArray): ByteArray {
        for (j in 1 until buffer.size) {
            buffer[j] = (buffer[j].toInt() xor gamma[(j - 1) % 16].toInt()).toByte()
        }
        return buffer
    }

    fun updateGamma(key: ByteArray) {
        key.copyInto(gamma, endIndex = minOf(key.size, 16))
    }

    companion object {
        // ---- Command builders ----

        fun requestGetKey(): ByteArray = NinebotDecoder.buildCommand(
            NinebotZAddr.APP, NinebotZAddr.KEY_GENERATOR, Comm.GET_KEY, 0x00,
        )

        fun requestSerialNumber(): ByteArray = NinebotDecoder.buildCommand(
            NinebotZAddr.APP, NinebotZAddr.CONTROLLER, Comm.READ, Param.SERIAL_NUMBER,
        )

        fun requestLiveData(): ByteArray = NinebotDecoder.buildCommand(
            NinebotZAddr.APP, NinebotZAddr.CONTROLLER, Comm.READ, Param.LIVE_DATA,
        )

        fun requestBleVersion(): ByteArray = NinebotDecoder.buildCommand(
            NinebotZAddr.APP, NinebotZAddr.CONTROLLER, Comm.READ, NinebotZParam.BLE_VERSION,
        )

        fun setLedMode(mode: Int): ByteArray = NinebotDecoder.buildCommand(
            NinebotZAddr.APP, NinebotZAddr.CONTROLLER, Comm.WRITE, NinebotZParam.LED_MODE,
            byteArrayOf((mode and 0xFF).toByte(), ((mode shr 8) and 0xFF).toByte()),
        )

        fun setLockMode(lock: Int): ByteArray = NinebotDecoder.buildCommand(
            NinebotZAddr.APP, NinebotZAddr.CONTROLLER, Comm.WRITE, NinebotZParam.LOCK_MODE,
            byteArrayOf((lock and 0xFF).toByte(), ((lock shr 8) and 0xFF).toByte()),
        )

        fun setSpeakerVolume(volume: Int): ByteArray {
            val shifted = volume shl 3
            return NinebotDecoder.buildCommand(
                NinebotZAddr.APP, NinebotZAddr.CONTROLLER, Comm.WRITE, NinebotZParam.SPEAKER_VOLUME,
                byteArrayOf((shifted and 0xFF).toByte(), ((shifted shr 8) and 0xFF).toByte()),
            )
        }
    }
}

// ---- Ninebot Z Addr constants ----

object NinebotZAddr {
    const val BMS1 = 0x11
    const val BMS2 = 0x12
    const val CONTROLLER = 0x14
    const val KEY_GENERATOR = 0x16
    const val APP = 0x3E
}

// ---- Ninebot Z extended Param constants ----

object NinebotZParam {
    const val GET_KEY = 0x00
    const val BLE_VERSION = 0x68
    const val LOCK_MODE = 0x70
    const val LIMITED_MODE = 0x72
    const val LIMIT_MODE_SPEED1_KM = 0x73
    const val LIMIT_MODE_SPEED = 0x74
    const val CALIBRATION = 0x75
    const val ALARMS = 0x7C
    const val ALARM1_SPEED = 0x7D
    const val ALARM2_SPEED = 0x7E
    const val ALARM3_SPEED = 0x7F
    const val LED_MODE = 0xC6
    const val LED_COLOR1 = 0xC8
    const val LED_COLOR2 = 0xCA
    const val LED_COLOR3 = 0xCC
    const val LED_COLOR4 = 0xCE
    const val PEDAL_SENSITIVITY = 0xD2
    const val DRIVE_FLAGS = 0xD3
    const val SPEAKER_VOLUME = 0xF5
}

// ---- NinebotZ Unpacker: similar to Ninebot but header 5A A5 and len + 9 ----

class NinebotZUnpacker {

    private enum class State { UNKNOWN, STARTED, COLLECTING }

    private var state = State.UNKNOWN
    private var buffer = mutableListOf<Byte>()
    private var expectedLen = 0

    /**
     * Feed a single byte. Returns a complete frame (without 5A A5 header) when done.
     */
    fun feed(b: Byte): ByteArray? {
        return when (state) {
            State.UNKNOWN -> {
                if (b == 0x5A.toByte()) {
                    state = State.STARTED
                }
                null
            }
            State.STARTED -> {
                if (b == 0xA5.toByte()) {
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
                    expectedLen = (b.toInt() and 0xFF) + 9
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
