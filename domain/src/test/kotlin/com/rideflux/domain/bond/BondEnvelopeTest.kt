package com.rideflux.domain.bond

import com.rideflux.domain.bond.BondEnvelope.OpenResult
import com.rideflux.domain.bond.BondEnvelope.PassphraseProblem
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BondEnvelopeTest {
    // Tests use the lowest accepted work factor so the suite stays fast.
    private val fast = BondEnvelope.MIN_ITERATIONS
    private val pass = "correct horse battery".toCharArray()
    private val now = Instant.parse("2026-10-05T01:02:03Z")

    private fun entry(mac: String = "AA:BB:CC:DD:EE:FF") =
        BondEntry(mac, BondFamily.XIAOMI_MI, ByteArray(12) { (it * 7).toByte() }, "My M365", "M365")

    private fun seal(entries: List<BondEntry> = listOf(entry()), passphrase: CharArray = pass) =
        BondEnvelope.seal(entries, passphrase, now, fast)

    @Test fun `a sealed file opens with the same passphrase`() {
        val file = seal(listOf(entry(), entry("11:22:33:44:55:66")))
        val opened = BondEnvelope.open(file, pass) as OpenResult.Opened
        assertEquals(listOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"), opened.payload.entries.map { it.mac })
        assertEquals(entry(), opened.payload.entries[0])
        assertEquals(0, opened.payload.skippedUnsupported)
    }

    @Test fun `the file has the documented header and never contains the secret in clear`() {
        val file = seal()
        assertEquals("RFBOND", String(file.copyOfRange(0, 6), Charsets.US_ASCII))
        assertEquals(1, file[6].toInt())
        assertEquals(fast, ByteBuffer.wrap(file, 7, 4).int)
        val secretHex = entry().credential().joinToString("") { "%02x".format(it) }
        assertFalse(String(file, Charsets.ISO_8859_1).contains(secretHex))
        assertFalse(String(file, Charsets.ISO_8859_1).contains("My M365"))
    }

    @Test fun `every seal uses a fresh salt and nonce`() {
        val a = seal()
        val b = seal()
        assertNotEquals(a.toList().subList(11, 39), b.toList().subList(11, 39))
        assertFalse(a.contentEquals(b))
    }

    @Test fun `the wrong passphrase is reported without leaking anything`() {
        val result = BondEnvelope.open(seal(), "another long passphrase".toCharArray())
        assertEquals(OpenResult.WrongPassphraseOrCorrupt, result)
    }

    @Test fun `the passphrase array is left untouched`() {
        val copy = pass.copyOf()
        seal()
        BondEnvelope.open(seal(), pass)
        assertArrayEquals(copy, pass)
    }

    @Test fun `any tampered byte is detected`() {
        val file = seal()
        for (index in listOf(11, 25, 39, file.size / 2, file.size - 1)) {
            val bad = file.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertEquals("byte $index", OpenResult.WrongPassphraseOrCorrupt, BondEnvelope.open(bad, pass))
        }
        val otherIterations = file.copyOf().also {
            ByteBuffer.wrap(it, 7, 4).putInt(fast + 1)
        }
        assertEquals(OpenResult.WrongPassphraseOrCorrupt, BondEnvelope.open(otherIterations, pass))
    }

    @Test fun `files that are not backups are rejected before any key derivation`() {
        val file = seal()
        assertEquals(OpenResult.NotABondFile, BondEnvelope.open(ByteArray(10), pass))
        assertEquals(OpenResult.NotABondFile, BondEnvelope.open(ByteArray(0), pass))
        assertEquals(OpenResult.NotABondFile, BondEnvelope.open(file.copyOf().also { it[0] = 'X'.code.toByte() }, pass))
        assertEquals(OpenResult.UnsupportedVersion(2), BondEnvelope.open(file.copyOf().also { it[6] = 2 }, pass))
        assertEquals(OpenResult.TooLarge, BondEnvelope.open(ByteArray(BondEnvelope.MAX_FILE_BYTES + 1), pass))
        for (bad in listOf(1, BondEnvelope.MIN_ITERATIONS - 1, BondEnvelope.MAX_ITERATIONS + 1, -5)) {
            val file2 = file.copyOf().also { ByteBuffer.wrap(it, 7, 4).putInt(bad) }
            assertTrue(BondEnvelope.open(file2, pass) is OpenResult.Malformed)
        }
    }

    /** Builds a correctly authenticated file around an arbitrary plaintext. */
    private fun sealRaw(plain: ByteArray): ByteArray {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val header = ByteBuffer.allocate(39).put("RFBOND".toByteArray()).put(1).putInt(fast).put(salt).put(nonce).array()
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pass, salt, fast, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    @Test fun `an authenticated but malformed document is reported as malformed`() {
        val result = BondEnvelope.open(sealRaw("{\"schema\":\"nope\",\"entries\":[]}".toByteArray()), pass)
        assertTrue(result is OpenResult.Malformed)
        assertEquals("Unsupported schema", (result as OpenResult.Malformed).reason)
        assertTrue(BondEnvelope.open(sealRaw("not json".toByteArray()), pass) is OpenResult.Malformed)
    }

    @Test fun `a hand built file in the documented layout opens`() {
        val json = """{"schema":"rideflux-bond/v1","createdAt":"t","entries":[{"mac":"AA:BB:CC:DD:EE:FF","family":"xiaomi_mi","credentialHex":"000102030405060708090a0b","label":"L"}]}"""
        val opened = BondEnvelope.open(sealRaw(json.toByteArray()), pass) as OpenResult.Opened
        assertEquals("L", opened.payload.entries.single().label)
        assertNull(opened.payload.entries.single().model)
    }

    @Test fun `weak passphrases and bad parameters are refused when sealing`() {
        assertThrows(IllegalArgumentException::class.java) { seal(passphrase = "short".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { seal(passphrase = "aaaaaaaaaaaa".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { seal(entries = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            BondEnvelope.seal(listOf(entry()), pass, now, BondEnvelope.MIN_ITERATIONS - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BondEnvelope.seal(listOf(entry()), pass, now, BondEnvelope.MAX_ITERATIONS + 1)
        }
    }

    @Test fun `passphrase strength rules`() {
        assertEquals(PassphraseProblem.TOO_SHORT, BondEnvelope.passphraseProblem("123456789".toCharArray()))
        assertEquals(PassphraseProblem.TOO_SIMPLE, BondEnvelope.passphraseProblem("ababababab".toCharArray()))
        assertNull(BondEnvelope.passphraseProblem("abcdefghij".toCharArray()))
        assertNull(BondEnvelope.passphraseProblem("correct horse battery".toCharArray()))
    }

    @Test fun `file name carries the date and the extension`() {
        assertEquals("rideflux-bonds-20261005.rfbond", BondEnvelope.suggestedFileName(now))
        assertEquals("rfbond", BondEnvelope.FILE_EXTENSION)
    }

    @Test fun `the default work factor is at least the recommended 600000`() {
        assertTrue(BondEnvelope.DEFAULT_ITERATIONS >= 600_000)
    }

    @Test fun `isBondFile detects valid and invalid files`() {
        assertTrue(BondEnvelope.isBondFile(seal()))
        assertFalse(BondEnvelope.isBondFile(byteArrayOf(1, 2, 3)))
        assertFalse(BondEnvelope.isBondFile("NOT_A_BOND_FILE_HEADER_PADDING_PADDING".toByteArray()))
        assertFalse(BondEnvelope.isBondFile(ByteArray(64)))
    }
}

