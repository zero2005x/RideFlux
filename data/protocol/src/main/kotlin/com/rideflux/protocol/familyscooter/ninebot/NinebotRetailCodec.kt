package com.rideflux.protocol.familyscooter.ninebot

import com.rideflux.domain.safety.DangerTier
import com.rideflux.domain.safety.MotionInterlock
import com.rideflux.protocol.familyscooter.RetailFraming
import com.rideflux.protocol.familyscooter.RetailFraming.u
import com.rideflux.protocol.familyscooter.m365.M365Codec

/** SHU 4.2.1 `5A A5` framing, reconstructed from Dart AOT (L1; no retail wire capture). */
object NinebotRetailCodec {
    data class Frame(val source: Int, val destination: Int, val command: Int, val argument: Int,
                     val payload: ByteArray)

    /** Read length is a 16-bit little-endian payload, not the frame's length byte. */
    fun buildReadRequest(register: Int, readLength: Int = 2, destination: Int = 0x20): ByteArray {
        require(register in 0..255 && readLength in 1..0xffff && destination in 0..255)
        return frame(destination, 0x01, register,
            byteArrayOf(readLength.toByte(), (readLength ushr 8).toByte()))
    }

    /** Pre-encryption BLE-random request. Destination 0x04 is a derived pairing vector. */
    fun buildHandshakeStep1(destination: Int = 0x04): ByteArray = frame(destination, 0x5b, 0)

    /** Bond-state change: fail closed until three fresh zero-speed samples exist. */
    fun buildHandshakeStep2(appRandom: ByteArray, interlock: MotionInterlock,
                            nowMillis: Long, destination: Int = 0x04): ByteArray {
        require(appRandom.size == 16) { "appRandom must contain exactly 16 bytes" }
        interlock.requireAllowed(DangerTier.CRITICAL, nowMillis)
        return frame(destination, 0x5c, 0, appRandom)
    }

    /** SHU's 0x5D body is the 16-byte random proposed at 0x5C. */
    fun buildHandshakeStep3(confirmedPayload: ByteArray, interlock: MotionInterlock,
                            nowMillis: Long, destination: Int = 0x04): ByteArray {
        require(confirmedPayload.size == 16)
        interlock.requireAllowed(DangerTier.CRITICAL, nowMillis)
        return frame(destination, 0x5d, 0, confirmedPayload)
    }

    /** Restricted retail write surface. Reboot, power, and firmware opcodes have no encoder. */
    fun controlTier(register: Int): DangerTier = when (register) {
        0x70, 0x71 -> DangerTier.CRITICAL // lock/unlock reset the scooter
        0x78, 0x79, 0x07, 0x08, 0x09 -> DangerTier.FORBIDDEN // reboot, power, firmware
        else -> DangerTier.FORBIDDEN // no verified general-purpose write surface
    }

    fun requireControlAllowed(register: Int, interlock: MotionInterlock, nowMillis: Long) =
        interlock.requireAllowed(controlTier(register), nowMillis)

    /** Only the two lock registers and the observed value 1 are representable here. */
    fun buildWriteRequest(register: Int, payload: ByteArray, interlock: MotionInterlock,
                          nowMillis: Long, destination: Int = 0x20): ByteArray {
        require(register == 0x70 || register == 0x71) { "Unsupported retail write register" }
        require(payload.contentEquals(byteArrayOf(1, 0))) { "Lock value must be LE16 1" }
        requireControlAllowed(register, interlock, nowMillis)
        return frame(destination, 0x02, register, payload)
    }

    fun buildLockRequest(interlock: MotionInterlock, nowMillis: Long,
                         destination: Int = 0x20): ByteArray =
        buildWriteRequest(0x70, byteArrayOf(1, 0), interlock, nowMillis, destination)

    fun buildUnlockRequest(interlock: MotionInterlock, nowMillis: Long,
                           destination: Int = 0x20): ByteArray =
        buildWriteRequest(0x71, byteArrayOf(1, 0), interlock, nowMillis, destination)

    /** Validate one complete plaintext frame. Encrypted `55 AB` traffic is a separate layer. */
    fun decodeFrame(bytes: ByteArray): Frame? {
        if (bytes.size < 9 || bytes[0].u() != 0x5a || bytes[1].u() != 0xa5) return null
        val payloadLength = bytes[2].u()
        if (bytes.size != payloadLength + 9) return null
        if (!RetailFraming.verify(bytes, 2)) return null
        return Frame(bytes[3].u(), bytes[4].u(), bytes[5].u(), bytes[6].u(),
            bytes.copyOfRange(7, 7 + payloadLength))
    }

    /** Existing ES2 ESC reply mapping; register semantics still depend on the selected model. */
    fun readReplyPayload(bytes: ByteArray, register: Int, byteCount: Int): ByteArray? {
        if (register !in 0..255 || byteCount !in 1..255) return null
        val reply = decodeFrame(bytes) ?: return null
        if (reply.source != 0x23 || reply.destination != 0x3e ||
            reply.command != register || reply.argument != 0x04 ||
            reply.payload.size != byteCount) return null
        return reply.payload
    }

    /** ES2 B0..BB core shares word positions with M365; speed remains raw. */
    fun decodeEs2B0(payload: ByteArray): M365Codec.B0Block? = M365Codec.decodeB0(payload)

    /** Diagnostic candidate only. ES2 sign/scale is unverified; retain its unknown-speed guard. */
    fun decodeSpeedKmh(bytes: ByteArray): Float? {
        val raw = readReplyPayload(bytes, 0xb0, 32)?.let(M365Codec::decodeB0)?.speedRaw ?: return null
        return if (raw >= 0xff00) null else raw / 1_000f
    }

    enum class EscFamily { ES2, M365 }
    enum class RegisterMeaning { BATTERY_CURRENT_RAW, EXTERNAL_BATTERY_TEMPERATURE_C, UNKNOWN }

    fun registerMeaning(family: EscFamily, register: Int): RegisterMeaning = when {
        family == EscFamily.ES2 && register == 0x49 -> RegisterMeaning.BATTERY_CURRENT_RAW
        family == EscFamily.ES2 && register == 0x50 -> RegisterMeaning.EXTERNAL_BATTERY_TEMPERATURE_C
        family == EscFamily.M365 && register == 0x50 -> RegisterMeaning.BATTERY_CURRENT_RAW
        else -> RegisterMeaning.UNKNOWN
    }

    private fun frame(destination: Int, command: Int, argument: Int,
                      payload: ByteArray = ByteArray(0)): ByteArray {
        require(destination in 0..255 && payload.size <= 255)
        val body = byteArrayOf(payload.size.toByte(), 0x3e, destination.toByte(),
            command.toByte(), argument.toByte()) + payload
        return RetailFraming.appendChecksum(byteArrayOf(0x5a, 0xa5.toByte()) + body, body)
    }
}
