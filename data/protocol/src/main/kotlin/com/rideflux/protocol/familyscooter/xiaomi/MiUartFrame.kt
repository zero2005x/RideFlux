package com.rideflux.protocol.familyscooter.xiaomi

import java.security.SecureRandom

enum class MiCounterMode { INCREMENTING, LEGACY_ZERO }
data class MiSessionOptions(val counterMode: MiCounterMode = MiCounterMode.INCREMENTING)
enum class MiFrameError { MALFORMED, HEADER, CHECKSUM, AUTHENTICATION, COUNTER_EXHAUSTED, CLOSED }
class MiFrameException(val reason: MiFrameError) : Exception("Mi UART error: $reason")

/** Owns copies of session keys. Decrypt returns [size, address, command, attribute, payload]. */
class MiUartFrame(
    keys: MiLoginKeys,
    private val options: MiSessionOptions = MiSessionOptions(),
    private val randomBytes: () -> ByteArray = { ByteArray(4).also { SecureRandom().nextBytes(it) } },
) : AutoCloseable {
    private val appKey = keys.appKey.copyOf()
    private val devKey = keys.devKey.copyOf()
    private val appIv = keys.appIv.copyOf()
    private val devIv = keys.devIv.copyOf()
    private var nextCounter = 0
    private var closed = false

    @Synchronized
    fun encrypt(message: ByteArray): ByteArray {
        checkOpen()
        if (message.size < 4 || message.size != (message[0].toInt() and 255) + 2) fail(MiFrameError.MALFORMED)
        // LEGACY_ZERO reuses a CCM nonce under the session key; compatibility only, never the default.
        val counter = if (options.counterMode == MiCounterMode.LEGACY_ZERO) 0 else nextCounter
        if (counter > 0xFFFF) fail(MiFrameError.COUNTER_EXHAUSTED)
        if (options.counterMode == MiCounterMode.INCREMENTING) nextCounter++
        val random = randomBytes()
        if (random.size != 4) { random.fill(0); fail(MiFrameError.MALFORMED) }
        val plaintext = ByteArray(message.size - 1 + random.size).apply {
            message.copyInto(this, 0, 1)
            random.copyInto(this, message.size - 1)
        }
        random.fill(0)
        val nonce = nonce(appIv, counter)
        val encrypted = try { MiCcm.encrypt(appKey, nonce, plaintext) }
        finally { plaintext.fill(0); nonce.fill(0) }
        val frame = byteArrayOf(0x55, 0xAB.toByte(), message[0], counter.toByte(), (counter ushr 8).toByte()) + encrypted
        return frame + checksum(frame, 2, frame.size)
    }

    @Synchronized
    fun decrypt(frame: ByteArray): ByteArray {
        checkOpen()
        if (frame.size < 18) fail(MiFrameError.MALFORMED)
        if (frame[0] != 0x55.toByte() || frame[1] != 0xAB.toByte()) fail(MiFrameError.HEADER)
        val size = frame[2].toInt() and 255
        if (size < 2 || frame.size != size + 16) fail(MiFrameError.MALFORMED)
        val expected = checksum(frame, 2, frame.size - 2)
        if (expected[0] != frame[frame.size - 2] || expected[1] != frame.last()) fail(MiFrameError.CHECKSUM)
        val counter = (frame[3].toInt() and 255) or ((frame[4].toInt() and 255) shl 8)
        val nonce = nonce(devIv, counter)
        val plaintext = try { MiCcm.decrypt(devKey, nonce, frame.copyOfRange(5, frame.size - 2)) }
        catch (_: MiCcm.AuthenticationException) { fail(MiFrameError.AUTHENTICATION) }
        finally { nonce.fill(0) }
        return try {
            ByteArray(plaintext.size - 3).apply {
                this[0] = frame[2]
                plaintext.copyInto(this, 1, 0, plaintext.size - 4)
            }
        }
        finally { plaintext.fill(0) }
    }

    @Synchronized
    override fun close() {
        listOf(appKey, devKey, appIv, devIv).forEach { it.fill(0) }
        closed = true
    }
    override fun toString() = "MiUartFrame(<redacted>)"
    private fun checkOpen() { if (closed) fail(MiFrameError.CLOSED) }
    private fun fail(reason: MiFrameError): Nothing = throw MiFrameException(reason)
    private fun nonce(iv: ByteArray, counter: Int) = ByteArray(12).apply {
        iv.copyInto(this)
        this[8] = counter.toByte(); this[9] = (counter ushr 8).toByte()
    }
    private fun checksum(bytes: ByteArray, start: Int, end: Int): ByteArray {
        var sum = 0
        for (index in start until end) sum += bytes[index].toInt() and 255
        val value = sum.inv() and 0xFFFF
        return byteArrayOf(value.toByte(), (value ushr 8).toByte())
    }
}
