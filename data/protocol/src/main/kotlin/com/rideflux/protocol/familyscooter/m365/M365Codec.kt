package com.rideflux.protocol.familyscooter.m365

import com.rideflux.domain.telemetry.ScooterTelemetry
import com.rideflux.protocol.familyscooter.RetailFraming
import com.rideflux.protocol.familyscooter.RetailFraming.u
import kotlin.math.abs

/** Xiaomi 55 AA retail register reads. Source: ninebot-docs protocol.md/M365ESC.md (L1). */
object M365Codec {
    data class B0Block(
        val errorCode: Int,
        val warningCode: Int,
        val batteryPercent: Int,
        /** B5 wire word, retained unsigned for diagnostics; M365 velocity is signed LE16. */
        val speedRaw: Int,
        val totalDistanceMetres: Long,
        val tripDistanceMetres: Int,
        val frameTemperatureRaw: Int,
    ) {
        /** Speed magnitude in either direction; only the exact zero word means stopped. */
        val speedKmh: Float?
            get() = if (speedRaw !in 0..0xffff) null else abs(speedRaw.toShort().toInt()) / 1_000f

        fun toTelemetry(timestampMillis: Long) = ScooterTelemetry(
            timestampMillis = timestampMillis,
            speedKmh = speedKmh,
            batteryPercent = batteryPercent.toFloat(),
            totalDistanceMetres = totalDistanceMetres,
            tripDistanceMetres = tripDistanceMetres.toLong(),
            // L3: scooter-apps/hardware-evidence/SPEED-FIELD-ANALYSIS.md:28-30, signed BB / 10.
            frameTemperatureC = frameTemperatureRaw / 10f,
        )
    }

    /** Length counts command, argument, and the mandatory one-byte read size. */
    fun readRequest(register: Int, byteCount: Int, destination: Int = 0x20): ByteArray {
        require(register in 0..255 && byteCount in 1..255 && destination in 0..255)
        val body = byteArrayOf(3, destination.toByte(), 1, register.toByte(), byteCount.toByte())
        return RetailFraming.appendChecksum(byteArrayOf(0x55, 0xaa.toByte()) + body, body)
    }

    fun b0BlockRequest(): ByteArray = readRequest(0xb0, 0x20)

    /** Separate 0x48 read; the voltage register is outside the B0 block. */
    fun decodeBatteryVoltageV(register48: ByteArray): Float? =
        if (register48.size == 2) (register48[0].u() or (register48[1].u() shl 8)) / 100f else null

    /** Separate 0x50 read. The sign/scale is not pinned, so expose the raw word only. */
    fun decodeBatteryCurrentRaw(register50: ByteArray): Int? =
        if (register50.size == 2) register50[0].u() or (register50[1].u() shl 8) else null

    /** Validates a complete wiki-style reply and returns only its register payload. */
    fun readReplyPayload(frame: ByteArray, register: Int, byteCount: Int): ByteArray? {
        if (register !in 0..255 || byteCount !in 1..255 || frame.size != byteCount + 8) return null
        if (frame[0].u() != 0x55 || frame[1].u() != 0xaa || frame[2].u() != byteCount + 2) return null
        // Wiki reply: cmd=read, arg=register. Source address 0x23 denotes reply from ESC.
        if (frame[3].u() != 0x23 || frame[4].u() != 1 || frame[5].u() != register) return null
        if (!RetailFraming.verify(frame, 2)) return null
        return frame.copyOfRange(6, frame.size - 2)
    }

    /** The 0xB0..0xBF block is 16 word-addressed registers = 32 bytes. */
    fun decodeB0(payload: ByteArray): B0Block? {
        if (payload.size != 32) return null
        fun u16(reg: Int): Int {
            val at = (reg - 0xb0) * 2
            return payload[at].u() or (payload[at + 1].u() shl 8)
        }
        val soc = u16(0xb4)
        if (soc !in 0..100) return null
        val odometerAt = (0xb7 - 0xb0) * 2
        val odometer = (0..3).fold(0L) { acc, i -> acc or (payload[odometerAt + i].u().toLong() shl (8 * i)) }
        return B0Block(
            errorCode = u16(0xb0), warningCode = u16(0xb1),
            batteryPercent = soc, speedRaw = u16(0xb5),
            totalDistanceMetres = odometer, tripDistanceMetres = u16(0xb9) * 10,
            frameTemperatureRaw = u16(0xbb).toShort().toInt(),
        )
    }
}
