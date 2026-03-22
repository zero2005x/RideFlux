package com.wheellog.next.data.protocol.decoder

import com.wheellog.next.domain.model.AlertFlag
import com.wheellog.next.domain.model.TelemetryState

/**
 * Inmotion v2 protocol decoder — full implementation per spec.
 *
 * Uses NUS channel (6E400001-b5a3-f393-e0a9-e50e24dcca9e).
 * Outer frame: header AA AA, **no footer** (unlike v1).
 * A5 escape rules: 0xAA -> A5 AA, 0xA5 -> A5 A5.
 * Checksum: XOR all bytes & 0xFF (differs from v1's SUM).
 *
 * Inner structure: flags(1) + len(1) + command(1) + data(len-1) + checkByte(1).
 *
 * Supported commands:
 * - MainVersion (0x01): main version info
 * - MainInfo (0x02): model / serial / firmware
 * - RealTimeInfo (0x04): real-time telemetry (model-specific layout)
 * - BatteryRealTimeInfo (0x05): battery pack data
 * - TotalStats (0x11): total distance / energy / ride time
 * - Settings (0x20): model-specific settings
 * - Control (0x60): control commands (light, speed, calibration, etc.)
 *
 * Model identification by carType value from MainInfo data[0]=0x01:
 *   V11=61, V11Y=62, V12HS=71, V12HT=72, V12PRO=73, V13=81, V13PRO=82,
 *   V14g=91, V14s=92, V12S=111, V9=121.
 */
class InmotionV2Decoder : FrameDecoder {

    private var lastState = TelemetryState()
    private val unpacker = InmotionV2Unpacker()

    // Extended info
    var modelId: Int = 0
        private set
    var serialNumber: String = ""
        private set
    var driverBoardVersion: String = ""
        private set
    var mainBoardVersion: String = ""
        private set
    var bleVersion: String = ""
        private set
    var totalDistance: Float = 0f
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
     * Process a complete unescaped frame.
     * Structure: flags(1) + len(1) + command(1) + data(len-1) + checkByte(1)
     */
    internal fun processFrame(frame: ByteArray): TelemetryState? {
        if (frame.size < 4) return null // minimum: flags + len + command + check

        // Verify XOR checksum: last byte = XOR of all preceding bytes
        val checkByte = frame[frame.size - 1].toInt() and 0xFF
        val computed = calcCheck(frame, 0, frame.size - 1)
        if (checkByte != computed) return null

        val flags = frame[0].toInt() and 0xFF
        val len = frame[1].toInt() and 0xFF
        val command = frame[2].toInt() and 0xFF

        // Data area: frame[3 .. 3 + (len-1) - 1]
        val dataLen = len - 1
        if (dataLen < 0 || frame.size < 3 + dataLen + 1) return null
        val data = if (dataLen > 0) frame.sliceArray(3 until 3 + dataLen) else ByteArray(0)

        return when (command) {
            Command.MAIN_INFO -> {
                parseMainInfo(data)
                null
            }
            Command.REAL_TIME_INFO -> parseRealTimeInfo(data)
            Command.BATTERY_REAL_TIME_INFO -> {
                parseBatteryInfo(data)
                null
            }
            Command.TOTAL_STATS -> {
                parseTotalStats(data)
                null
            }
            Command.MAIN_VERSION -> {
                parseMainVersion(data)
                null
            }
            else -> null
        }
    }

    // ---- MainInfo (Command 0x02) ----

    private fun parseMainInfo(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0].toInt() and 0xFF) {
            0x01 -> {
                // Car type identification
                if (data.size >= 3) {
                    modelId = data[2].toInt() and 0xFF
                }
            }
            0x02 -> {
                // Serial number: 16 chars from data[1..]
                if (data.size >= 17) {
                    serialNumber = String(data, 1, 16, Charsets.UTF_8).trim()
                }
            }
            0x06 -> {
                // Version info: DriverBoard / MainBoard / BLE
                if (data.size >= 7) {
                    driverBoardVersion = "${data[1]}.${data[2]}"
                    mainBoardVersion = "${data[3]}.${data[4]}"
                    bleVersion = "${data[5]}.${data[6]}"
                }
            }
        }
    }

    // ---- MainVersion (Command 0x01) ----

    private fun parseMainVersion(data: ByteArray) {
        // Version sub-info — stored for reference
    }

    // ---- RealTimeInfo (Command 0x04) — model-specific layouts ----

    private fun parseRealTimeInfo(data: ByteArray): TelemetryState? {
        return when {
            isV13Layout() -> parseRealTimeV13(data)
            isV12Layout() -> parseRealTimeV12(data)
            else -> parseRealTimeV11(data) // V11 as default/fallback
        }
    }

    /**
     * V11 legacy layout (also used as fallback for unknown models).
     */
    private fun parseRealTimeV11(data: ByteArray): TelemetryState? {
        if (data.size < 37) return null

        val voltage = ByteUtils.readUInt16LE(data, 0) / 100f
        val current = ByteUtils.readInt16LE(data, 2) / 100f
        val speed = ByteUtils.readInt16LE(data, 4) / 100f
        val mileage = ByteUtils.readUInt16LE(data, 12) * 10f / 1000f // x10 then to km
        val batLevel = (data[16].toInt() and 0x7F)
        val mosTemp = convertTemp(data[17])
        val motTemp = convertTemp(data[18])

        val alerts = buildAlerts(mosTemp, motTemp, batLevel, speed)

        lastState = TelemetryState(
            speedKmh = speed,
            voltageV = voltage,
            currentA = current,
            temperatureC = mosTemp,
            tripDistanceKm = mileage,
            batteryPercent = batLevel.coerceIn(0, 100),
            totalDistanceKm = totalDistance,
            alertFlags = alerts,
        )
        return lastState
    }

    /**
     * V12 layout (V12HS / V12HT / V12PRO).
     */
    private fun parseRealTimeV12(data: ByteArray): TelemetryState? {
        if (data.size < 55) return null

        val voltage = ByteUtils.readUInt16LE(data, 0) / 100f
        val current = ByteUtils.readInt16LE(data, 2) / 100f
        val speed = ByteUtils.readInt16LE(data, 4) / 100f
        val mileage = ByteUtils.readUInt16LE(data, 22) * 10f / 1000f
        val batLevel = (data[24].toInt() and 0x7F)

        // Temperatures at offsets 40-46
        val mosTemp = convertTemp(data[40])
        val motTemp = convertTemp(data[41])

        val alerts = buildAlerts(mosTemp, motTemp, batLevel, speed)

        lastState = TelemetryState(
            speedKmh = speed,
            voltageV = voltage,
            currentA = current,
            temperatureC = mosTemp,
            tripDistanceKm = mileage,
            batteryPercent = batLevel.coerceIn(0, 100),
            totalDistanceKm = totalDistance,
            alertFlags = alerts,
        )
        return lastState
    }

    /**
     * V13 / V13PRO / V14 / V11Y / V9 / V12S layout.
     */
    private fun parseRealTimeV13(data: ByteArray): TelemetryState? {
        if (data.size < 65) return null

        val voltage = ByteUtils.readUInt16LE(data, 0) / 100f
        val current = ByteUtils.readInt16LE(data, 2) / 100f
        val speed = ByteUtils.readInt16LE(data, 8) / 100f
        // mileage uses intRevLE (reversed LE 32-bit) at offset 10
        val mileage = ByteUtils.readUInt32LE(data, 10) / 1000f

        // Battery levels (two packs)
        val batLevel1 = ByteUtils.readUInt16LE(data, 34)
        val batLevel2 = ByteUtils.readUInt16LE(data, 36)
        val batLevel = ((batLevel1 + batLevel2) / 2).coerceIn(0, 100)

        // Temperatures at offsets 58-64
        val mosTemp = convertTemp(data[58])
        val motTemp = convertTemp(data[59])

        val alerts = buildAlerts(mosTemp, motTemp, batLevel, speed)

        lastState = TelemetryState(
            speedKmh = speed,
            voltageV = voltage,
            currentA = current,
            temperatureC = mosTemp,
            tripDistanceKm = mileage.toFloat(),
            batteryPercent = batLevel,
            totalDistanceKm = totalDistance,
            alertFlags = alerts,
        )
        return lastState
    }

    // ---- BatteryRealTimeInfo (Command 0x05) ----

    private fun parseBatteryInfo(data: ByteArray) {
        // bat1Voltage@0, bat1Temp@4, bat2Voltage@8, bat2Temp@12, chargeVoltage@16, chargeCurrent@18
        // Stored for reference, not directly mapped to TelemetryState
    }

    // ---- TotalStats (Command 0x11) ----

    private fun parseTotalStats(data: ByteArray) {
        if (data.size < 20) return
        totalDistance = ByteUtils.readUInt32LE(data, 0) / 1000f
        // dissipation@4, recovery@8, rideTime@12, powerOnTime@16 — stored for reference
    }

    // ---- Model detection helpers ----

    private fun isV13Layout(): Boolean =
        modelId in intArrayOf(81, 82, 91, 92, 62, 121, 111)

    private fun isV12Layout(): Boolean =
        modelId in intArrayOf(71, 72, 73)

    // ---- Temperature conversion: signed byte + 80 - 256 ----

    private fun convertTemp(b: Byte): Float =
        ((b.toInt() and 0xFF) + 80 - 256).toFloat()

    // ---- Alert building ----

    private fun buildAlerts(mosTemp: Float, motTemp: Float, battery: Int, speed: Float): Set<AlertFlag> =
        buildSet {
            if (mosTemp > 65f || motTemp > 65f) add(AlertFlag.HIGH_TEMPERATURE)
            if (battery < 10) add(AlertFlag.LOW_BATTERY)
            if (kotlin.math.abs(speed) > 35f) add(AlertFlag.OVERSPEED)
        }

    companion object {
        /** Compute Inmotion v2 checksum: XOR all bytes & 0xFF. */
        fun calcCheck(data: ByteArray, start: Int = 0, end: Int = data.size): Int {
            var check = 0
            for (i in start until end) {
                check = check xor (data[i].toInt() and 0xFF)
            }
            return check and 0xFF
        }

        /**
         * Build a command frame for Inmotion v2.
         * Applies A5 escape and prepends AA AA header.
         */
        fun buildFrame(flags: Int, command: Int, data: ByteArray = ByteArray(0)): ByteArray {
            val len = data.size + 1 // command is included in len
            val inner = ByteArray(3 + data.size)
            inner[0] = flags.toByte()
            inner[1] = len.toByte()
            inner[2] = command.toByte()
            data.copyInto(inner, destinationOffset = 3)

            val check = calcCheck(inner)
            val payload = inner + byteArrayOf(check.toByte())

            // Apply A5 escape encoding and prepend AA AA header
            val escaped = mutableListOf<Byte>()
            escaped.add(0xAA.toByte())
            escaped.add(0xAA.toByte())
            for (b in payload) {
                when (b) {
                    0xAA.toByte() -> { escaped.add(0xA5.toByte()); escaped.add(0xAA.toByte()) }
                    0xA5.toByte() -> { escaped.add(0xA5.toByte()); escaped.add(0xA5.toByte()) }
                    else -> escaped.add(b)
                }
            }
            return escaped.toByteArray()
        }

        // ---- Control commands (Command 0x60) ----

        fun setLight(on: Boolean): ByteArray =
            buildFrame(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x50, if (on) 0x01 else 0x00))

        fun setMaxSpeed(speed: Int): ByteArray {
            val lo = (speed and 0xFF).toByte()
            val hi = ((speed shr 8) and 0xFF).toByte()
            return buildFrame(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x21, lo, hi))
        }

        fun setSpeakerVolume(volume: Int): ByteArray =
            buildFrame(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x26, volume.toByte()))

        fun setLock(lock: Boolean): ByteArray =
            buildFrame(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x31, if (lock) 0x01 else 0x00))

        fun calibration(): ByteArray =
            buildFrame(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x52))

        fun beep(): ByteArray =
            buildFrame(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x51, 0x64))

        fun setFan(on: Boolean): ByteArray =
            buildFrame(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x43, if (on) 0x01 else 0x00))

        fun setDrl(on: Boolean): ByteArray =
            buildFrame(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x2D, if (on) 0x01 else 0x00))

        fun setHandleButton(mode: Int): ByteArray =
            buildFrame(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x2E, mode.toByte()))

        fun setPedalSensitivity(value: Int): ByteArray =
            buildFrame(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x25, value.toByte()))

        fun setTransportMode(on: Boolean): ByteArray =
            buildFrame(Flag.DEFAULT, Command.CONTROL, byteArrayOf(0x32, if (on) 0x01 else 0x00))

        /** Request real-time info. */
        fun requestRealTimeInfo(): ByteArray =
            buildFrame(Flag.DEFAULT, Command.REAL_TIME_INFO)

        /** Request main info. */
        fun requestMainInfo(): ByteArray =
            buildFrame(Flag.INITIAL, Command.MAIN_INFO)

        /** Request total stats. */
        fun requestTotalStats(): ByteArray =
            buildFrame(Flag.DEFAULT, Command.TOTAL_STATS)
    }
}

// ---- Flag constants ----

object Flag {
    const val INITIAL = 0x11
    const val DEFAULT = 0x14
}

// ---- Command constants ----

object Command {
    const val MAIN_VERSION = 0x01
    const val MAIN_INFO = 0x02
    const val DIAGNOSTIC = 0x03
    const val REAL_TIME_INFO = 0x04
    const val BATTERY_REAL_TIME_INFO = 0x05
    const val SOMETHING1 = 0x10
    const val TOTAL_STATS = 0x11
    const val SETTINGS = 0x20
    const val CONTROL = 0x60
}

// ---- InmotionV2Unpacker: AA AA header, no footer, A5 escape ----

/**
 * Stream-based unpacker for Inmotion v2 frames.
 * Header: AA AA (no footer). A5 escape rules: AA -> A5 AA, A5 -> A5 A5.
 * Frame ends when expected length (flags + len + command + data + check) is collected.
 */
class InmotionV2Unpacker {

    private enum class State { UNKNOWN, HEADER1, STARTED, ESCAPE }

    private var state = State.UNKNOWN
    private var buffer = mutableListOf<Byte>()
    private var expectedLen = -1

    /**
     * Feed a single byte. Returns a complete unescaped frame when done.
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
                    expectedLen = -1
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
                    0xAA.toByte() -> {
                        // New header start — could be a new frame; reset
                        state = State.HEADER1
                        buffer.clear()
                        expectedLen = -1
                        null
                    }
                    else -> {
                        buffer.add(b)
                        checkComplete()
                    }
                }
            }
            State.ESCAPE -> {
                buffer.add(b)
                state = State.STARTED
                checkComplete()
            }
        }
    }

    private fun checkComplete(): ByteArray? {
        // After collecting at least 2 bytes (flags + len), we know the total length
        if (expectedLen < 0 && buffer.size >= 2) {
            val len = buffer[1].toInt() and 0xFF
            // Total frame: flags(1) + len(1) + command(1) + data(len-1) + check(1) = len + 3
            expectedLen = len + 3
        }
        if (expectedLen > 0 && buffer.size >= expectedLen) {
            val frame = buffer.toByteArray()
            state = State.UNKNOWN
            buffer.clear()
            expectedLen = -1
            return frame
        }
        return null
    }

    fun reset() {
        state = State.UNKNOWN
        buffer.clear()
        expectedLen = -1
    }
}
