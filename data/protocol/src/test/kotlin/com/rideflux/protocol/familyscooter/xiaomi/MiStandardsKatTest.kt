package com.rideflux.protocol.familyscooter.xiaomi

import org.junit.Assert.*
import org.junit.Test

class MiStandardsKatTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val key = hex("005e8f4d8e0cbf4e1ceeb5d87a275848")
    private val nonce = hex("0ec3ac452b547b9062aac8fa")
    private val aad = hex("2f1821aa57e5278ffd33c17d46615b77363149dbc98470413f6543a6b749f2ca")
    private val plaintext = hex("b6f345204526439daf84998f380dcfb4b4167c959c04ff65")
    private val ciphertext = hex("9575e16f35da3c88a19c26a7b762044f4d7bbbafeff05d754829e2a7752fa3a14890972884b511d8")

    @Test fun officialNistCcmVectorIsUsedWithoutAdaptingInputsOrOutput() {
        // NIST CAVP ccmtestvectors.zip / VNT128.rsp Count=50:
        // Alen=32 Plen=24 Tlen=16 Nlen=12. Primary published bytes, not ninebotcrypto code.
        // https://csrc.nist.gov/projects/cryptographic-algorithm-validation-program/cavp-testing-block-cipher-modes
        assertArrayEquals(ciphertext, MiCcm.encryptWithTag(key, nonce, plaintext, aad, 16))
        assertArrayEquals(plaintext, MiCcm.decryptWithTag(key, nonce, ciphertext, aad, 16))
        // Mi's public fixed four-byte tag keeps the ciphertext stream, but has a different MAC.
        val mi = ciphertext.copyOf(plaintext.size + 4)
        val actualMi = MiCcm.encrypt(key, nonce, plaintext, aad)
        assertArrayEquals(ciphertext.copyOf(plaintext.size), actualMi.copyOf(plaintext.size))
        assertFalse(mi.contentEquals(actualMi))
        assertArrayEquals(plaintext, MiCcm.decrypt(key, nonce, actualMi, aad))
        // Tag length is included in B0, so this shortened NIST tag must NOT authenticate as Mi.
        expectAuthentication { MiCcm.decrypt(key, nonce, mi, aad) }
    }

    @Test fun rejectsWrongTagTamperedCiphertextAndAad() {
        for (index in listOf(0, ciphertext.lastIndex)) {
            val corrupt = ciphertext.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
            expectAuthentication { MiCcm.decryptWithTag(key, nonce, corrupt, aad, 16) }
        }
        expectAuthentication { MiCcm.decryptWithTag(key, nonce, ciphertext, aad.copyOf().apply { this[0] = 0 }, 16) }
        val mi = MiCcm.encrypt(key, nonce, plaintext, aad)
        expectAuthentication { MiCcm.decrypt(key, nonce, mi.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }, aad) }
    }

    @Test fun rejectsShortNonceAndInvalidTagLengthsBeforeDecrypting() {
        for (size in listOf(0, 6, 11, 13)) {
            assertThrows(IllegalArgumentException::class.java) { MiCcm.encrypt(key, ByteArray(size), plaintext) }
            assertThrows(IllegalArgumentException::class.java) { MiCcm.decryptWithTag(key, ByteArray(size), ciphertext, aad, 16) }
        }
        for (tag in listOf(0, 3, 5, 18)) {
            assertThrows(IllegalArgumentException::class.java) { MiCcm.encryptWithTag(key, nonce, plaintext, aad, tag) }
            assertThrows(IllegalArgumentException::class.java) { MiCcm.decryptWithTag(key, nonce, ciphertext, aad, tag) }
        }
        assertThrows(IllegalArgumentException::class.java) { MiCcm.decryptWithTag(key, nonce, ByteArray(15), aad, 16) }
    }

    @Test fun hkdfMatchesRfc5869AppendixA3EmptySaltAndInfo() {
        // RFC 5869 Appendix A.3 SHA-256, 22 bytes 0b, L=42.
        assertArrayEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            MiHkdf.derive(ByteArray(22) { 0x0b }, null, byteArrayOf(), 42))
    }

    private fun expectAuthentication(action: () -> Unit) {
        assertThrows(MiCcm.AuthenticationException::class.java) { action() }
    }
}
