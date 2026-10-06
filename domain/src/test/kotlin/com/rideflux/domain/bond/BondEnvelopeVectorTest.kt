package com.rideflux.domain.bond

import java.security.SecureRandom
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Known-answer test for the `.rfbond` format. The expected bytes were produced by an independent
 * implementation (Python `cryptography`, OpenSSL: PBKDF2-HMAC-SHA256 + AES-256-GCM, header as
 * additional data), so a second app can check its own implementation against the same vector.
 * The vector is also listed in `docs/BOND_BACKUP.md`.
 */
class BondEnvelopeVectorTest {
    private class FixedRandom(vararg chunks: ByteArray) : SecureRandom() {
        private val queue = ArrayDeque(chunks.toList())
        override fun nextBytes(bytes: ByteArray) { queue.removeFirst().copyInto(bytes) }
    }

    private val passphrase = "correct horse battery".toCharArray()
    private val salt = ByteArray(16) { it.toByte() }
    private val nonce = ByteArray(12) { (0xa0 + it).toByte() }
    private val expectedHex =
        "5246424f4e4401000186a0000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a6a7a8a9aaab3d8433dda0721f193807e6681bf94d567" +
        "45abf6a49da176ea9317691966a4255a2430e53a42f7d85010417d3c9081d0ba5f520db5f221d84f4560558f6506e1e802b10266494730" +
        "680ce6d62a12d12139fd3e9174860f9ccae5c24a66de121223eac3cf6e4d1f8327f0d4cc80651c7ca28330957297df288abb74c82d374c" +
        "42a14d18d9b0b024e13d8229d8fbe7307b2fec4d82999ac5896fe2a9462f8a9488e15ae3f5ef1045d957d5beb8a882206f1c90923dc083" +
        "71c398bdd56f11e4ae72a27014188e3d5b484b8511c80de5defaaf31a7299bac0f8dccb"

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val entry = BondEntry("AA:BB:CC:DD:EE:FF", BondFamily.XIAOMI_MI,
        ByteArray(12) { it.toByte() }, "My M365", "M365")

    @Test fun `sealing reproduces the independently computed file byte for byte`() {
        val file = BondEnvelope.seal(listOf(entry), passphrase, Instant.parse("2026-10-05T00:00:00Z"),
            iterations = 100_000, random = FixedRandom(salt, nonce))
        assertEquals(expectedHex, file.joinToString("") { "%02x".format(it) })
    }

    @Test fun `the independently computed file opens to the same entry`() {
        val opened = BondEnvelope.open(hex(expectedHex), passphrase) as BondEnvelope.OpenResult.Opened
        assertEquals(listOf(entry), opened.payload.entries)
        assertArrayEquals(ByteArray(12) { it.toByte() }, opened.payload.entries.single().credential())
    }
}
