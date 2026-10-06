package com.rideflux.protocol.familyscooter.xiaomi

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 5869 SHA-256; no salt means the required 32 zero bytes. */
object MiHkdf {
    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun derive(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        require(length in 0..8160) { "Invalid HKDF output length" }
        val prk = hmac(salt ?: ByteArray(32), ikm)
        var previous = ByteArray(0)
        val output = ByteArray(length)
        try {
            var position = 0
            var counter = 1
            while (position < length) {
                val input = ByteArray(previous.size + info.size + 1).apply {
                    previous.copyInto(this)
                    info.copyInto(this, previous.size)
                    this[lastIndex] = counter.toByte()
                }
                val next = try { hmac(prk, input) } finally { input.fill(0) }
                previous.fill(0)
                previous = next
                val count = minOf(32, length - position)
                previous.copyInto(output, position, 0, count)
                position += count
                counter++
            }
            return output
        } catch (error: Exception) {
            output.fill(0)
            throw error
        } finally {
            prk.fill(0)
            previous.fill(0)
        }
    }
}
