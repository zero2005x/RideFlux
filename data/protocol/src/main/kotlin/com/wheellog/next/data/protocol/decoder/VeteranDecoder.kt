package com.wheellog.next.data.protocol.decoder

import com.wheellog.next.domain.model.AlertFlag
import com.wheellog.next.domain.model.TelemetryState

/**
 * Veteran protocol decoder — full implementation per spec.
 *
 * Header: DC 5A 5C (3 bytes), then len(1), then payload(len bytes).
 * Byte order: Big-Endian.
 * Old format (len <= 38): no CRC.
 * New format (len > 38): CRC32 appended at data tail.
 *
 * Field table:
 * - [4..5]  voltage (BE unsigned, V * 100)
 * - [6..7]  speed (BE signed, km/h * 10)
 * - [8..11] distance (intRevBE)
 * - [12..15] totalDistance (intRevBE)
 * - [16..17] phaseCurrent (BE signed, * 10)
 * - [18..19] temperature (BE signed, degrees C)
 * - [20..21] autoOffSec
 * - [24..25] speedAlert (* 10)
 * - [26..27] speedTiltback (* 10)
 * - [28..29] version (mVer — determines model)
 * - [30]     pedalsMode
 * - [32..33] pitchAngle (BE signed)
 * - [34..35] hwPwm
 *
 * Validation: byte[22]==0x00, (byte[23]&0xFE)==0x00, byte[30]==0x00||0x07.
 * Uses VeteranUnpacker state machine for stream reassembly.
 *
 * SmartBMS sub-packets (mVer >= 5): buff[46] pnum sub-number:
 * - 0/4: BMS current
 * - 1/5: Cells 0-14
 * - 2/6: Cells 15-29
 * - 3/7: Cells 30-41 + temperatures
 *
 * Model identification by mVer:
 *   0-1=Sherman, 2=Abrams, 3=Sherman S, 4=Patton, 5=Lynx,
 *   6=Sherman L, 7=Patton S, 8=Oryx, 42=Nosfet Apex, 43=Nosfet Aero, 44=Nosfet Aeon.
 *
 * Commands are ASCII strings: "SETh"/"SETm"/"SETs", "SetLightON"/"SetLightOFF",
 * "CLEARMETER", "b" (old beep).
 */
class VeteranDecoder : FrameDecoder {

    private var lastState = TelemetryState()
    private val unpacker = VeteranUnpacker()

    // Extended fields
    var mVer: Int = 0
        private set
    var pedalsMode: Int = 0
        private set
    var speedAlert: Float = 0f
        private set
    var speedTiltback: Float = 0f
        private set
    var pitchAngle: Float = 0f
        private set
    var modelName: String = ""
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
     * Process a complete frame (payload after DC 5A 5C header + len byte).
     */
    internal fun processFrame(frame: ByteArray): TelemetryState? {
        if (frame.size < 36) return null

        // Validation checks
        if (frame[22] != 0x00.toByte()) return null
        if ((frame[23].toInt() and 0xFE) != 0x00) return null
        val pm = frame[30].toInt() and 0xFF
        if (pm != 0x00 && pm != 0x01 && pm != 0x02 && pm != 0x07) return null

        // CRC32 check for new format (len > 38)
        if (frame.size > 38) {
            if (!verifyCrc32(frame)) return null
        }

        // Parse main fields — all offsets are relative to frame[0] = first byte after len
        // But our frame already starts at the first data byte (offset 4 in the raw stream).
        // Since unpacker collects bytes after header+len, the frame starts at original offset[4].
        // So frame[0] corresponds to original byte[4].
        val voltage = ByteUtils.readUInt16BE(frame, 0) / 100f
        val speed = ByteUtils.readInt16BE(frame, 2) / 10f
        val tripDistance = ByteUtils.intRevBE(frame, 4).toFloat() / 1000f
        val totalDist = ByteUtils.intRevBE(frame, 8).toFloat() / 1000f
        val phaseCurrent = ByteUtils.readInt16BE(frame, 12) / 10f
        val temperature = ByteUtils.readInt16BE(frame, 14).toFloat()
        speedAlert = ByteUtils.readUInt16BE(frame, 20) / 10f
        speedTiltback = ByteUtils.readUInt16BE(frame, 22) / 10f
        mVer = ByteUtils.readUInt16BE(frame, 24)
        pedalsMode = frame[26].toInt() and 0xFF
        pitchAngle = ByteUtils.readInt16BE(frame, 28).toFloat()

        modelName = identifyModel(mVer)
        val battery = estimateBattery(voltage, mVer)

        val alerts = buildSet {
            if (temperature > 65f) add(AlertFlag.HIGH_TEMPERATURE)
            if (battery < 10) add(AlertFlag.LOW_BATTERY)
            if (kotlin.math.abs(speed) > speedAlert && speedAlert > 0) add(AlertFlag.OVERSPEED)
        }

        lastState = TelemetryState(
            speedKmh = speed,
            voltageV = voltage,
            currentA = phaseCurrent,
            temperatureC = temperature,
            tripDistanceKm = tripDistance,
            totalDistanceKm = totalDist,
            batteryPercent = battery,
            alertFlags = alerts,
        )
        return lastState
    }

    // ---- Model identification by mVer ----

    private fun identifyModel(ver: Int): String = when (ver) {
        0, 1 -> "Sherman"
        2 -> "Abrams"
        3 -> "Sherman S"
        4 -> "Patton"
        5 -> "Lynx"
        6 -> "Sherman L"
        7 -> "Patton S"
        8 -> "Oryx"
        42 -> "Nosfet Apex"
        43 -> "Nosfet Aero"
        44 -> "Nosfet Aeon"
        else -> "Veteran ($ver)"
    }

    // ---- Battery estimation by voltage group ----

    internal fun estimateBattery(voltage: Float, ver: Int): Int {
        // Voltage ranges vary by model group
        val (minV, maxV) = when {
            ver < 4 -> 75f to 100.8f       // Sherman series (24S)
            ver in intArrayOf(4, 7, 43) -> 93.75f to 126f   // Patton / Patton S / Aero (30S)
            ver in intArrayOf(5, 6, 42, 44) -> 112.5f to 151.2f // Lynx / Sherman L / Apex / Aeon (36S)
            ver == 8 -> 131.25f to 176.4f   // Oryx (42S)
            else -> 75f to 100.8f
        }
        return ((voltage - minV) / (maxV - minV) * 100).toInt().coerceIn(0, 100)
    }

    // ---- CRC32 verification for new format ----

    private fun verifyCrc32(frame: ByteArray): Boolean {
        if (frame.size < 4) return false
        // CRC32 is in the last 4 bytes
        val dataEnd = frame.size - 4
        val expectedCrc = ByteUtils.readUInt32BE(frame, dataEnd)
        val computed = computeCrc32(frame, 0, dataEnd)
        return expectedCrc == computed
    }

    companion object {
        // ---- CRC32 implementation ----

        private val CRC_TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) {
                c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
            }
            c
        }

        fun computeCrc32(data: ByteArray, start: Int = 0, end: Int = data.size): Long {
            var crc = 0xFFFFFFFF.toInt()
            for (i in start until end) {
                crc = CRC_TABLE[(crc xor (data[i].toInt() and 0xFF)) and 0xFF] xor (crc ushr 8)
            }
            return (crc xor 0xFFFFFFFF.toInt()).toLong() and 0xFFFFFFFFL
        }

        // ---- Command builders: Veteran uses ASCII strings ----

        /** Set pedals hard. */
        fun pedalHard(): ByteArray = "SETh".toByteArray(Charsets.US_ASCII)

        /** Set pedals medium. */
        fun pedalMedium(): ByteArray = "SETm".toByteArray(Charsets.US_ASCII)

        /** Set pedals soft. */
        fun pedalSoft(): ByteArray = "SETs".toByteArray(Charsets.US_ASCII)

        /** Light on. */
        fun lightOn(): ByteArray = "SetLightON".toByteArray(Charsets.US_ASCII)

        /** Light off. */
        fun lightOff(): ByteArray = "SetLightOFF".toByteArray(Charsets.US_ASCII)

        /** Clear trip meter. */
        fun clearMeter(): ByteArray = "CLEARMETER".toByteArray(Charsets.US_ASCII)

        /** Short beep (old firmware, mVer < 3). */
        fun beepOld(): ByteArray = "b".toByteArray(Charsets.US_ASCII)
    }
}

// ---- VeteranUnpacker: DC 5A 5C header state machine ----

/**
 * Stream-based unpacker for Veteran frames.
 * Header: DC 5A 5C, then 1-byte length, then payload bytes.
 */
class VeteranUnpacker {

    private enum class State { UNKNOWN, HEADER1, HEADER2, LEN_SEARCH, COLLECTING }

    private var state = State.UNKNOWN
    private var buffer = mutableListOf<Byte>()
    private var expectedLen = 0

    /**
     * Feed a single byte. Returns the complete payload (after header+len) when done.
     */
    fun feed(b: Byte): ByteArray? {
        return when (state) {
            State.UNKNOWN -> {
                if (b == 0xDC.toByte()) {
                    state = State.HEADER1
                }
                null
            }
            State.HEADER1 -> {
                if (b == 0x5A.toByte()) {
                    state = State.HEADER2
                } else {
                    state = State.UNKNOWN
                }
                null
            }
            State.HEADER2 -> {
                if (b == 0x5C.toByte()) {
                    state = State.LEN_SEARCH
                } else {
                    state = State.UNKNOWN
                }
                null
            }
            State.LEN_SEARCH -> {
                expectedLen = b.toInt() and 0xFF
                if (expectedLen == 0) {
                    state = State.UNKNOWN
                    null
                } else {
                    state = State.COLLECTING
                    buffer.clear()
                    null
                }
            }
            State.COLLECTING -> {
                buffer.add(b)
                if (buffer.size >= expectedLen) {
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
