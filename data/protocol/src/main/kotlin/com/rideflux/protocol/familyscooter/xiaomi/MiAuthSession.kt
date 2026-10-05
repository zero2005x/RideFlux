package com.rideflux.protocol.familyscooter.xiaomi

import com.rideflux.domain.transport.MiAuthChar
import com.rideflux.domain.transport.MiAuthTransport
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface MiLoginResult {
    /** Caller owns keys and must wipe them when the session closes. */
    class Success(val keys: MiLoginKeys) : MiLoginResult {
        override fun toString() = "MiLoginResult.Success(<redacted>)"
    }
    data object Timeout : MiLoginResult
    data object ProofMismatch : MiLoginResult
    data class ProtocolError(val reason: MiProtocolReason) : MiLoginResult
}

sealed interface MiRegistrationResult {
    /** Caller owns token and must wipe after storing or logging in. */
    class Success(val token: ByteArray) : MiRegistrationResult {
        fun wipe() { token.fill(0) }
        override fun toString() = "MiRegistrationResult.Success(<redacted>)"
    }
    data object ConsentRequired : MiRegistrationResult
    data object Timeout : MiRegistrationResult
    data object UserDidNotPressButton : MiRegistrationResult
    data class ProtocolError(val reason: MiProtocolReason) : MiRegistrationResult
}

enum class MiRegistrationProgress { IDLE, REQUESTING_INFO, WAITING_FOR_BUTTON, EXCHANGING_KEYS, CONFIRMING }

/**
 * L2 implementation, tested against a simulator only. Login never registers implicitly.
 * Caller retains the borrowed login token; temporary copies are wiped on every exit.
 * A session serializes operations and requires sole consumption of the transport auth flow.
 */
class MiAuthSession(
    private val transport: MiAuthTransport,
    private val stepTimeoutMillis: Long = 10_000,
    private val buttonTimeoutMillis: Long = 30_000,
    private val frameSpacingMillis: Long = 20,
    private val randomBytes: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) },
) {
    init {
        require(stepTimeoutMillis > 0 && buttonTimeoutMillis > 0 && frameSpacingMillis >= 0) { "Invalid auth timing" }
    }
    private val mutex = Mutex()
    private val mutableProgress = MutableStateFlow(MiRegistrationProgress.IDLE)
    val registrationProgress: StateFlow<MiRegistrationProgress> = mutableProgress.asStateFlow()

    suspend fun login(token: ByteArray): MiLoginResult = mutex.withLock {
        if (token.size != 12) return@withLock MiLoginResult.ProtocolError(MiProtocolReason.INVALID_TOKEN)
        val tokenCopy = token.copyOf()
        val appRandom = ByteArray(16)
        var scooterRandom: ByteArray? = null
        var proof: ByteArray? = null
        var keys: MiLoginKeys? = null
        try {
            randomBytes(appRandom)
            withMailbox { mailbox ->
                mailbox.write(MiAuthChar.UPNP, byteArrayOf(0x24, 0, 0, 0))
                mailbox.send(0x0B, appRandom)
                scooterRandom = mailbox.receive()
                if (scooterRandom.size != 16) throw MiProtocolException(MiProtocolReason.INVALID_RANDOM)
                proof = mailbox.receive()
                if (proof.size != 32) throw MiProtocolException(MiProtocolReason.INVALID_PROOF)
                val loginKeys = MiKeys.login(tokenCopy, appRandom, scooterRandom)
                keys = loginKeys
                if (!MessageDigest.isEqual(loginKeys.scooterProof, proof)) return@withMailbox MiLoginResult.ProofMismatch
                mailbox.send(0x0A, loginKeys.loginInfo)
                mailbox.expect(0x21)
                MiLoginResult.Success(loginKeys).also { keys = null }
            }
        } catch (_: TimeoutCancellationException) { MiLoginResult.Timeout }
        catch (error: CancellationException) { throw error }
        catch (error: MiProtocolException) { MiLoginResult.ProtocolError(error.reason) }
        catch (_: Exception) { MiLoginResult.ProtocolError(MiProtocolReason.TRANSPORT_FAILURE) }
        finally {
            tokenCopy.fill(0); appRandom.fill(0); scooterRandom?.fill(0); proof?.fill(0); keys?.wipe()
        }
    }

    /** Explicit consent represents the stationary/button dialog; no speed is available before auth. */
    suspend fun register(consent: Boolean): MiRegistrationResult = mutex.withLock {
        if (!consent) return@withLock MiRegistrationResult.ConsentRequired
        try { withMailbox { registerExchange(it) } }
        catch (_: UserButtonTimeout) { MiRegistrationResult.UserDidNotPressButton }
        catch (_: TimeoutCancellationException) { MiRegistrationResult.Timeout }
        catch (error: CancellationException) { throw error }
        catch (error: MiProtocolException) { MiRegistrationResult.ProtocolError(error.reason) }
        catch (_: Exception) { MiRegistrationResult.ProtocolError(MiProtocolReason.TRANSPORT_FAILURE) }
        finally { mutableProgress.value = MiRegistrationProgress.IDLE }
    }

    private suspend fun registerExchange(mailbox: MiAuthMailbox): MiRegistrationResult {
        var pair: KeyPair? = null
        var setup: MiSetupKeys? = null
        val temporaries = mutableListOf<ByteArray>()
        try {
                mutableProgress.value = MiRegistrationProgress.REQUESTING_INFO
                mailbox.write(MiAuthChar.UPNP, byteArrayOf(0xA2.toByte(), 0, 0, 0))
                val remoteInfo = mailbox.receive().also(temporaries::add)
                if (remoteInfo.size <= 4) throw MiProtocolException(MiProtocolReason.INVALID_REMOTE_INFO)
                pair = MiEcdh.generate()
                val publicKey = MiEcdh.publicRaw(pair.public as ECPublicKey).also(temporaries::add)
                sendPublicKey(mailbox, publicKey)
                val remoteKey = mailbox.receive().also(temporaries::add)
                val secret = deriveSecret(pair, remoteKey).also(temporaries::add)
                setup = MiKeys.setup(secret)
                val did = remoteInfo.copyOfRange(4, remoteInfo.size).also(temporaries::add)
                val ciphertext = MiCcm.encrypt(setup.didKey, DID_NONCE, did, DID_AAD).also(temporaries::add)
                // Reference announces exactly two chunks; other DID lengths remain unknown.
                if (ciphertext.size !in 19..36) throw MiProtocolException(MiProtocolReason.UNSUPPORTED_DID_LENGTH)
                mailbox.send(0, ciphertext)
                mutableProgress.value = MiRegistrationProgress.CONFIRMING
                mailbox.write(MiAuthChar.UPNP, byteArrayOf(0x13, 0, 0, 0))
                mailbox.expect(0x11)
                return MiRegistrationResult.Success(setup.token.copyOf())
        } finally {
            temporaries.forEach { it.fill(0) }; setup?.wipe(); destroy(pair)
        }
    }

    private suspend fun sendPublicKey(mailbox: MiAuthMailbox, publicKey: ByteArray) {
        mailbox.write(MiAuthChar.UPNP, byteArrayOf(0x15, 0, 0, 0))
        try {
            mailbox.send(3, publicKey, buttonTimeoutMillis,
                onReadyWait = { mutableProgress.value = MiRegistrationProgress.WAITING_FOR_BUTTON },
                onReady = { mutableProgress.value = MiRegistrationProgress.EXCHANGING_KEYS },
            )
        } catch (error: TimeoutCancellationException) {
            if (mutableProgress.value == MiRegistrationProgress.WAITING_FOR_BUTTON) throw UserButtonTimeout()
            throw error
        }
    }

    private class UserButtonTimeout : Exception("Power-button confirmation timed out")

    private fun deriveSecret(pair: KeyPair, remoteKey: ByteArray): ByteArray = try {
        MiEcdh.sharedSecret(pair.private, remoteKey)
    } catch (_: IllegalArgumentException) { throw MiProtocolException(MiProtocolReason.INVALID_PUBLIC_KEY) }

    private suspend fun <T> withMailbox(block: suspend (MiAuthMailbox) -> T): T = coroutineScope {
        val mailbox = MiAuthMailbox(this, transport, stepTimeoutMillis, frameSpacingMillis)
        try { block(mailbox) }
        finally { withContext(NonCancellable) { mailbox.close() } }
    }

    private fun destroy(pair: KeyPair?) {
        // JCA provider internals cannot guarantee zeroing; best effort then release the reference.
        try { pair?.private?.destroy() } catch (_: Exception) { /* Provider limitation. */ }
    }

    override fun toString() = "MiAuthSession(<redacted>)"

    private companion object {
        val DID_NONCE = ByteArray(12) { (0x10 + it).toByte() }
        val DID_AAD = byteArrayOf(0x64, 0x65, 0x76, 0x49, 0x44)
    }
}
