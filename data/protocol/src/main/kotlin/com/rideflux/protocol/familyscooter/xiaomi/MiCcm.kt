package com.rideflux.protocol.familyscooter.xiaomi

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** RFC 3610 CCM with AES-128, four-byte tag and twelve-byte nonce (L=3). */
object MiCcm {
    class AuthenticationException : Exception("CCM authentication failed")

    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray {
        return encryptWithTag(key, nonce, plaintext, aad, 4)
    }

    // RFC 3610 section 2: even authentication lengths 4..16. Internal entry point lets
    // official NIST CAVP VNT128.rsp Count=50 exercise this same core without adapting a KAT.
    internal fun encryptWithTag(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray, tagBytes: Int): ByteArray {
        require(tagBytes in 4..16 && tagBytes % 2 == 0) { "Invalid CCM tag length" }
        validate(key, nonce, plaintext.size)
        val cipher = cipher(key)
        val mac = authenticate(cipher, nonce, plaintext, aad, tagBytes)
        val mask = counterBlock(cipher, nonce, 0)
        return try {
            val output = crypt(cipher, nonce, plaintext)
            output + ByteArray(tagBytes) { (mac[it].toInt() xor mask[it].toInt()).toByte() }
        } finally { mac.fill(0); mask.fill(0) }
    }

    @Throws(AuthenticationException::class)
    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray {
        return decryptWithTag(key, nonce, ciphertext, aad, 4)
    }

    internal fun decryptWithTag(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray, tagBytes: Int): ByteArray {
        require(tagBytes in 4..16 && tagBytes % 2 == 0) { "Invalid CCM tag length" }
        require(ciphertext.size >= tagBytes) { "Invalid CCM ciphertext length" }
        validate(key, nonce, ciphertext.size - tagBytes)
        val cipher = cipher(key)
        val data = ciphertext.copyOfRange(0, ciphertext.size - tagBytes)
        val plaintext = try { crypt(cipher, nonce, data) } finally { data.fill(0) }
        try {
            verify(cipher, nonce, plaintext, aad, ciphertext, tagBytes)
            return plaintext
        } catch (error: Exception) {
            plaintext.fill(0)
            throw error
        }
    }

    private fun verify(cipher: Cipher, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray, ciphertext: ByteArray, tagBytes: Int) {
        val mac = authenticate(cipher, nonce, plaintext, aad, tagBytes)
        val mask = counterBlock(cipher, nonce, 0)
        val expected = ByteArray(tagBytes) { (mac[it].toInt() xor mask[it].toInt()).toByte() }
        val actual = ciphertext.copyOfRange(ciphertext.size - tagBytes, ciphertext.size)
        try {
            if (!MessageDigest.isEqual(expected, actual)) throw AuthenticationException()
        } finally { mac.fill(0); mask.fill(0); expected.fill(0); actual.fill(0) }
    }

    private fun validate(key: ByteArray, nonce: ByteArray, size: Int) {
        require(key.size == 16 && nonce.size == 12 && size < 0x1000000) { "Invalid CCM parameters" }
    }

    // RFC 3610 needs AES on individual 16-byte blocks. CBC-MAC and CTR above compose CCM;
    // this primitive never encrypts a message directly in ECB mode.
    @Suppress("kotlin:S5542")
    private fun cipher(key: ByteArray) = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }

    private fun counter(nonce: ByteArray, value: Int) = ByteArray(16).apply {
        this[0] = 2
        nonce.copyInto(this, 1)
        this[13] = (value ushr 16).toByte()
        this[14] = (value ushr 8).toByte()
        this[15] = value.toByte()
    }

    private fun counterBlock(cipher: Cipher, nonce: ByteArray, value: Int): ByteArray {
        val input = counter(nonce, value)
        return try { cipher.doFinal(input) } finally { input.fill(0) }
    }

    private fun crypt(cipher: Cipher, nonce: ByteArray, data: ByteArray): ByteArray {
        val output = ByteArray(data.size)
        for (start in data.indices step 16) {
            val stream = counterBlock(cipher, nonce, start / 16 + 1)
            try {
                for (offset in 0 until minOf(16, data.size - start)) {
                    output[start + offset] = (data[start + offset].toInt() xor stream[offset].toInt()).toByte()
                }
            } finally { stream.fill(0) }
        }
        return output
    }

    private fun authenticate(cipher: Cipher, nonce: ByteArray, data: ByteArray, aad: ByteArray, tagBytes: Int): ByteArray {
        var state = ByteArray(16)
        fun block(bytes: ByteArray) {
            val input = ByteArray(16) { (state[it].toInt() xor bytes[it].toInt()).toByte() }
            val next = try { cipher.doFinal(input) } finally { input.fill(0) }
            state.fill(0)
            state = next
        }
        try {
            val first = counter(nonce, data.size).apply {
                this[0] = ((if (aad.isEmpty()) 0 else 0x40) or (((tagBytes - 2) / 2) shl 3) or 2).toByte()
            }
            try { block(first) } finally { first.fill(0) }
            if (aad.isNotEmpty()) authenticateAad(aad, ::block)
            for (start in data.indices step 16) {
                val piece = ByteArray(16)
                data.copyInto(piece, 0, start, minOf(start + 16, data.size))
                try { block(piece) } finally { piece.fill(0) }
            }
            return state
        } catch (error: Exception) {
            state.fill(0)
            throw error
        }
    }

    private fun authenticateAad(aad: ByteArray, block: (ByteArray) -> Unit) {
        val prefix = if (aad.size < 0xFF00) byteArrayOf((aad.size ushr 8).toByte(), aad.size.toByte())
        else byteArrayOf(0xFF.toByte(), 0xFE.toByte(), (aad.size ushr 24).toByte(),
            (aad.size ushr 16).toByte(), (aad.size ushr 8).toByte(), aad.size.toByte())
        val padded = ByteArray((prefix.size + aad.size + 15) / 16 * 16)
        prefix.copyInto(padded)
        aad.copyInto(padded, prefix.size)
        try {
            for (start in padded.indices step 16) {
                val piece = padded.copyOfRange(start, start + 16)
                try { block(piece) } finally { piece.fill(0) }
            }
        } finally { padded.fill(0); prefix.fill(0) }
    }
}
