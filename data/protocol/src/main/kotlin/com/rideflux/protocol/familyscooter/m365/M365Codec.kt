package com.rideflux.protocol.familyscooter.m365

import com.rideflux.domain.telemetry.ScooterTelemetry
import com.rideflux.protocol.familyscooter.RetailFraming
import com.rideflux.protocol.familyscooter.RetailFraming.u
import io.github.zero2005x.pev.core.codec.xiaomi.XiaomiBmsDecoder
import io.github.zero2005x.pev.core.codec.xiaomi.XiaomiMotorInfoDecoder
import io.github.zero2005x.pev.core.codec.xiaomi.XiaomiPdu
import io.github.zero2005x.pev.core.telemetry.FieldId
import kotlin.math.abs
import kotlin.math.roundToInt

/** M365 core consumer. Retail framing/authentication and legacy DTO display policy stay in RideFlux. */
object M365Codec {
    data class B0Block(
        val errorCode: Int,
        val warningCode: Int,
        val batteryPercent: Int,
        /** B5 raw word retained unsigned; the legacy display accessor uses signed LE16 magnitude. */
        val speedRaw: Int,
        val totalDistanceMetres: Long,
        val tripDistanceMetres: Int,
        val frameTemperatureRaw: Int,
    ) {
        /**
         * Legacy RideFlux display policy: signed LE16 magnitude, including very slow reverse motion.
         * The shared core's forward/wrap policy differs at 0x8001..0xBFFF; do not use this
         * compatibility accessor as evidence that the physical interpretation was vehicle-verified.
         */
        val speedKmh: Float?
            get() = if (speedRaw !in 0..0xffff) null else abs(speedRaw.toShort().toInt()) / 1_000f

        fun toTelemetry(timestampMillis: Long) = ScooterTelemetry(
            timestampMillis = timestampMillis,
            speedKmh = speedKmh,
            batteryPercent = batteryPercent.toFloat(),
            totalDistanceMetres = totalDistanceMetres,
            tripDistanceMetres = tripDistanceMetres.toLong(),
        )
    }

    /** Length counts command, argument, and the mandatory one-byte read size. */
    fun readRequest(register: Int, byteCount: Int, destination: Int = 0x20): ByteArray {
        require(register in 0..255 && byteCount in 1..255 && destination in 0..255)
        val body = XiaomiPdu.read(register, byteCount, destination)
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
        val decoded = XiaomiMotorInfoDecoder.decode(payload, 0)
        val soc = decoded.usableValue(FieldId.SOC_PERCENT)?.toInt() ?: return null
        val odometer = decoded.usableValue(FieldId.TOTAL_DISTANCE_M)?.toLong() ?: return null
        val temperature = decoded.usableValue(FieldId.TEMP_FRAME) ?: return null
        fun u16(reg: Int): Int {
            val at = (reg - 0xb0) * 2
            return requireNotNull(XiaomiBmsDecoder.readU16(payload, at))
        }
        return B0Block(
            errorCode = u16(0xb0), warningCode = u16(0xb1),
            batteryPercent = soc, speedRaw = u16(0xb5),
            // Existing B9 trip projection is legacy-only; the core leaves this meaning unresolved.
            totalDistanceMetres = odometer, tripDistanceMetres = u16(0xb9) * 10,
            frameTemperatureRaw = (temperature * 10).roundToInt(),
        )
    }
}
