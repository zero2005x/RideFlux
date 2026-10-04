package com.rideflux.protocol.familybms.ant

import com.rideflux.domain.telemetry.Celsius
import com.rideflux.domain.telemetry.Millivolt
import com.rideflux.domain.telemetry.SmartBmsTelemetry

/** Read-only Ant/Mayi generation-2 status codec. Generation-1 is a different protocol. */
object AntCodec {
    fun statusPoll(): ByteArray = byteArrayOf(0x7e, 0xa1.toByte(), 0x01, 0, 0, 0xbe.toByte(),
        0x18, 0x55, 0xaa.toByte(), 0x55)

    fun decode(frame: ByteArray, timestampMillis: Long): SmartBmsTelemetry? {
        if (frame.size < 10 || frame[0].u() != 0x7e || frame[1].u() != 0xa1 ||
            frame[frame.lastIndex - 1].u() != 0xaa || frame.last().u() != 0x55) return null
        val length = frame[5].u()
        if (frame.size != length + 10 || frame[2].u() != 0x11) return null
        val received = frame[frame.size - 4].u() or (frame[frame.size - 3].u() shl 8)
        if (crc16Modbus(frame, 1, frame.size - 4) != received) return null
        val payload = frame.copyOfRange(6, 6 + length)
        if (payload.size < 34) return null
        val tempCount = payload[2].u()
        val cellCount = payload[3].u()
        if (cellCount !in 16..32 || tempCount > 8 || length != 106 + 2 * (cellCount + tempCount)) return null
        val offset = 2 * (cellCount + tempCount)
        if (64 + offset > length) return null
        val cells = (0 until cellCount).map { Millivolt(le16(payload, 28 + it * 2)) }
        if (cells.any { it.value !in 1_000..5_000 }) return null
        val temps = (0 until tempCount).map { Celsius(le16(payload, 28 + 2 * cellCount + it * 2).toShort().toFloat()) }
        val voltage = le16(payload, 32 + offset) * 0.01f
        // The pack voltage must agree with the cells within a conservative 2 V tolerance.
        if (kotlin.math.abs(cells.sumOf { it.value }.toFloat() / 1_000f - voltage) > 2f) return null
        val soh = le16(payload, 38 + offset).toFloat()
        if (soh !in 0f..100f) return null
        val remainingMicroAh = le32(payload, 48 + offset)
        return SmartBmsTelemetry(
            timestampMillis = timestampMillis,
            totalVoltageV = voltage,
            currentA = le16(payload, 34 + offset).toShort() * 0.1f,
            remainingCapacityAh = remainingMicroAh / 1_000_000f,
            stateOfHealthPercent = soh,
            cellVoltages = cells,
            temperatures = temps,
            chargeMosEnabled = payload[40 + offset].u() != 0,
            dischargeMosEnabled = payload[41 + offset].u() != 0,
        )
    }

    private fun crc16Modbus(bytes: ByteArray, from: Int, until: Int): Int {
        var crc = 0xffff
        for (i in from until until) {
            crc = crc xor bytes[i].u()
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xa001 else crc ushr 1 }
        }
        return crc
    }

    private fun le16(b: ByteArray, i: Int) = b[i].u() or (b[i + 1].u() shl 8)
    private fun le32(b: ByteArray, i: Int) = b[i].u().toLong() or
        (b[i + 1].u().toLong() shl 8) or (b[i + 2].u().toLong() shl 16) or (b[i + 3].u().toLong() shl 24)
    private fun Byte.u() = toInt() and 0xff
}
