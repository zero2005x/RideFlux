package com.rideflux.domain.bond

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The `.rfbond` container: a passphrase-encrypted `rideflux-bond/v1` document.
 *
 * ```
 * "RFBOND" | version(1) | iterations(u32 BE) | salt(16) | nonce(12) | AES-256-GCM(ciphertext || tag)
 * ```
 * The 39-byte header is authenticated as additional data, so changing the iteration count, the
 * salt or the version invalidates the file. The key comes from PBKDF2-HMAC-SHA256.
 *
 * Only [BondEntry] values can be sealed. There is no field for anything else, which is how the
 * Rokid bridge token is kept out of every backup.
 */
object BondEnvelope {
    const val FILE_EXTENSION = "rfbond"
    const val MIME_TYPE = "application/octet-stream"
    const val MIN_PASSPHRASE_LENGTH = 10
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 10_000_000
    const val MAX_FILE_BYTES = 256 * 1024

    private val MAGIC = "RFBOND".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private val HEADER_BYTES = MAGIC.size + 1 + 4 + SALT_BYTES + NONCE_BYTES

    enum class PassphraseProblem { TOO_SHORT, TOO_SIMPLE }

    sealed interface OpenResult {
        class Opened(val payload: BondPayload) : OpenResult
        data object WrongPassphraseOrCorrupt : OpenResult
        data object NotABondFile : OpenResult
        data object TooLarge : OpenResult
        data class UnsupportedVersion(val version: Int) : OpenResult
        data class Malformed(val reason: String) : OpenResult
    }

    /** Check if [file] matches the RFBOND magic and minimum container size. */
    fun isBondFile(file: ByteArray): Boolean =
        file.size >= HEADER_BYTES + TAG_BITS / 8 &&
            file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /** Why [passphrase] is unacceptable for a new backup, or null when it is fine. */
    fun passphraseProblem(passphrase: CharArray): PassphraseProblem? = when {
        passphrase.size < MIN_PASSPHRASE_LENGTH -> PassphraseProblem.TOO_SHORT
        passphrase.toSet().size < 4 -> PassphraseProblem.TOO_SIMPLE
        else -> null
    }

    fun suggestedFileName(now: Instant): String =
        "rideflux-bonds-" + now.toString().take(10).replace("-", "") + "." + FILE_EXTENSION

    /**
     * Encrypt [entries] under [passphrase]. [iterations] exists for tests; production callers use
     * the default. The passphrase array is not modified; the caller zeroes it.
     */
    fun seal(
        entries: List<BondEntry>,
        passphrase: CharArray,
        now: Instant = Instant.now(),
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(entries.isNotEmpty()) { "Nothing to export" }
        require(passphraseProblem(passphrase) == null) { "Passphrase is too weak" }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Unsupported iteration count" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = header(iterations, salt, nonce)
        val plain = BondPayloadCodec.write(entries, now.toString())
        try {
            val sealed = crypt(Cipher.ENCRYPT_MODE, passphrase, iterations, salt, nonce, header, plain)
            return header + sealed
        } finally {
            plain.fill(0)
        }
    }

    /** Decrypt and parse [file]. The caller zeroes [passphrase] and, on success, the entries. */
    fun open(file: ByteArray, passphrase: CharArray): OpenResult {
        if (file.size > MAX_FILE_BYTES) return OpenResult.TooLarge
        if (file.size < HEADER_BYTES + TAG_BITS / 8 ||
            !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return OpenResult.NotABondFile
        val buffer = ByteBuffer.wrap(file, MAGIC.size, file.size - MAGIC.size)
        val version = buffer.get().toInt() and 0xff
        if (version != VERSION) return OpenResult.UnsupportedVersion(version)
        val iterations = buffer.int
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return OpenResult.Malformed("Iteration count out of range")
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val header = file.copyOfRange(0, HEADER_BYTES)
        val sealed = file.copyOfRange(HEADER_BYTES, file.size)
        val plain = try {
            crypt(Cipher.DECRYPT_MODE, passphrase, iterations, salt, nonce, header, sealed)
        } catch (_: AEADBadTagException) {
            return OpenResult.WrongPassphraseOrCorrupt
        }
        try {
            return OpenResult.Opened(BondPayloadCodec.read(plain))
        } catch (e: BondFormatException) {
            return OpenResult.Malformed(e.message ?: "Invalid document")
        } finally {
            plain.fill(0)
        }
    }

    private fun header(iterations: Int, salt: ByteArray, nonce: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(VERSION.toByte()).putInt(iterations)
            .put(salt).put(nonce).array()

    private fun crypt(
        mode: Int, passphrase: CharArray, iterations: Int, salt: ByteArray, nonce: ByteArray,
        aad: ByteArray, input: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val raw = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("PBKDF2 unavailable", e)
        } finally {
            spec.clearPassword()
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(raw, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            return cipher.doFinal(input)
        } finally {
            raw.fill(0)
        }
    }
}
