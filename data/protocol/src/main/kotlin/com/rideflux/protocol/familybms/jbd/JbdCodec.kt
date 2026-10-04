package com.rideflux.protocol.familybms.jbd

import com.rideflux.domain.telemetry.Celsius
import com.rideflux.domain.telemetry.Millivolt
import com.rideflux.domain.telemetry.SmartBmsTelemetry

/** Read-only JBD frames. BLE notifications must be reassembled by the declared length. */
object JbdCodec {
    enum class ChecksumHypothesis { H1, H2 }

    fun readRequest(register: Int, hypothesis: ChecksumHypothesis = ChecksumHypothesis.H1): ByteArray {
        require(register == 0x03 || register == 0x04)
        val sum = if (hypothesis == ChecksumHypothesis.H1) (0x10000 - register) and 0xffff else 0
        return byteArrayOf(0xdd.toByte(), 0xa5.toByte(), register.toByte(), 0,
            (sum ushr 8).toByte(), sum.toByte(), 0x77)
    }

    fun decode(frame: ByteArray, timestampMillis: Long): SmartBmsTelemetry? {
        if (frame.size < 7 || frame[0].u() != 0xdd || frame.last().u() != 0x77) return null
        val length = frame[3].u()
        if (frame.size != length + 7 || frame[2].u() != 0) return null
        val sum = (2 until 4 + length).sumOf { frame[it].u() }
        val expected = (0x10000 - sum) and 0xffff
        if (expected != ((frame[4 + length].u() shl 8) or frame[5 + length].u())) return null
        return when (frame[1].u()) {
            0x03 -> decodeBasic(frame, length, timestampMillis)
            0x04 -> decodeCells(frame, length, timestampMillis)
            else -> null
        }
    }

    private fun decodeBasic(frame: ByteArray, n: Int, at: Long): SmartBmsTelemetry? {
        // The register byte is outside the response checksum: exact shape is mandatory.
        if (n < 23) return null
        val cells = frame[4 + 21].u()
        val ntcs = frame[4 + 22].u()
        if (cells !in 1..32 || ntcs > 16 || n != 23 + 2 * ntcs) return null
        val soc = frame[4 + 19].u()
        if (soc > 100) return null
        val mos = frame[4 + 20].u()
        return SmartBmsTelemetry(
            timestampMillis = at,
            totalVoltageV = be16(frame, 4).toFloat() * 0.01f,
            currentA = be16(frame, 6).toShort().toFloat() * 0.01f,
            remainingCapacityAh = be16(frame, 8).toFloat() * 0.01f,
            cycleCount = be16(frame, 12),
            temperatures = (0 until ntcs).map { Celsius((be16(frame, 4 + 23 + it * 2) - 2731) * 0.1f) },
            chargeMosEnabled = mos and 1 != 0,
            dischargeMosEnabled = mos and 2 != 0,
        )
    }

    private fun decodeCells(frame: ByteArray, n: Int, at: Long): SmartBmsTelemetry? {
        if (n !in 2..64 || n % 2 != 0) return null
        val values = (0 until n / 2).map { Millivolt(be16(frame, 4 + it * 2)) }
        // 0 mV is an unplugged/invalid cell, never a valid pack voltage.
        if (values.any { it.value !in 1_000..5_000 }) return null
        return SmartBmsTelemetry(timestampMillis = at, cellVoltages = values)
    }

    private fun be16(bytes: ByteArray, at: Int) = (bytes[at].u() shl 8) or bytes[at + 1].u()
    private fun Byte.u() = toInt() and 0xff
}
