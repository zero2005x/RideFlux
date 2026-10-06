package com.rideflux.protocol.familyscooter.xiaomi

import com.rideflux.domain.transport.MiAuthChar
import com.rideflux.domain.transport.MiAuthNotification
import com.rideflux.domain.transport.MiAuthTransport
import java.security.interfaces.ECPublicKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Implements the peer side for self-consistency only, never real-scooter compatibility. */
private class SimulatedMiScooter(didLength: Int = 20) : MiAuthTransport {
    private val notifications = Channel<MiAuthNotification>(Channel.UNLIMITED)
    override val authNotifications = notifications.receiveAsFlow()
    override val incoming: Flow<ByteArray> = emptyFlow()
    override suspend fun connect() = Unit
    override suspend fun disconnect() = Unit
    override suspend fun write(bytes: ByteArray) = Unit
    val token = ByteArray(12) { (it + 1).toByte() }
    val borrowedWrites = mutableListOf<ByteArray>()
    val emitted = mutableListOf<MiAuthNotification>()
    val parcels = ArrayDeque<ByteArray>()
    val inputs = mutableListOf<ByteArray>()
    val sentCommands = mutableListOf<Int>()
    var transform: (MiAuthChar, ByteArray) -> Pair<MiAuthChar, ByteArray>? = { char, bytes -> char to bytes }
    var writeFailure = false
    var blockAfterHeader = false
    var registration = false
    private var command = 0
    private var expectedFrames = 0
    private val random = ByteArray(16) { (it + 20).toByte() }
    private var keys: MiLoginKeys? = null
    private val pair = MiEcdh.generate()
    private val did = ByteArray(didLength) { (it + 40).toByte() }
    private var setup: MiSetupKeys? = null

    fun closeFlow() { notifications.close() }
    fun failFlow() { notifications.close(IllegalStateException("private platform details")) }

    private fun emit(char: MiAuthChar, bytes: ByteArray) {
        val changed = transform(char, bytes) ?: return
        val notification = MiAuthNotification(changed.first, changed.second)
        emitted.add(notification)
        notifications.trySend(notification).getOrThrow()
    }

    private fun response(opcode: Int) {
        if (opcode >= 0x100) emit(MiAuthChar.AVDTP, byteArrayOf(0, 0, 1, (opcode - 0x100).toByte()))
        else emit(MiAuthChar.UPNP, byteArrayOf(opcode.toByte(), 0, 0, 0))
    }

    private fun queueParcel(payload: ByteArray) {
        parcels.add(payload)
        if (parcels.size == 1) emit(MiAuthChar.AVDTP, MiParcel.header(0, MiParcel.fragment(payload).size))
    }

    override suspend fun writeAuth(characteristic: MiAuthChar, bytes: ByteArray) {
        borrowedWrites.add(bytes)
        if (writeFailure) throw IllegalStateException("transport failure")
        if (characteristic == MiAuthChar.UPNP) {
            when (bytes[0].toInt() and 255) {
                0xA2 -> {
                    registration = true
                    queueParcel(ByteArray(did.size + 4).also { did.copyInto(it, 4) })
                }
                0x13 -> response(0x11)
            }
        } else when {
            bytes.contentEquals(byteArrayOf(0, 0, 1, 1)) -> {
                MiParcel.fragment(parcels.first()).forEach { emit(MiAuthChar.AVDTP, it) }
            }
            bytes.contentEquals(byteArrayOf(0, 0, 1, 0)) -> {
                parcels.removeFirst()
                if (parcels.isNotEmpty()) emit(MiAuthChar.AVDTP, MiParcel.header(0, MiParcel.fragment(parcels.first()).size))
            }
            bytes.size == 6 && bytes[0] == 0.toByte() -> {
                command = bytes[3].toInt() and 255
                sentCommands.add(command)
                expectedFrames = MiParcel.frameCount(bytes)
                inputs.clear()
                response(0x101)
                if (blockAfterHeader) awaitCancellation()
            }
            else -> {
                inputs.add(bytes.copyOf())
                if (inputs.size == expectedFrames) finishPayload()
            }
        }
    }

    private fun finishPayload() {
        val payload = MiParcel.reassemble(inputs, expectedFrames)
        response(0x100)
        when (command) {
            0x0B -> {
                keys?.wipe()
                keys = MiKeys.login(token, payload, random)
                queueParcel(random.copyOf())
                queueParcel(keys!!.scooterProof.copyOf())
            }
            0x0A -> {
                check(payload.contentEquals(keys!!.loginInfo))
                response(0x21)
            }
            3 -> {
                val secret = MiEcdh.sharedSecret(pair.private, payload)
                setup?.wipe()
                setup = MiKeys.setup(secret)
                secret.fill(0)
                setup!!.token.copyInto(token)
                queueParcel(MiEcdh.publicRaw(pair.public as ECPublicKey))
            }
            0 -> {
                val decrypted = MiCcm.decrypt(setup!!.didKey, ByteArray(12) { (it + 0x10).toByte() }, payload,
                    byteArrayOf(0x64, 0x65, 0x76, 0x49, 0x44))
                assertArrayEquals(did, decrypted)
                decrypted.fill(0)
            }
        }
        payload.fill(0)
        inputs.forEach { it.fill(0) }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MiAuthSessionTest {
    private fun session(peer: SimulatedMiScooter, random: ByteArray? = null) = MiAuthSession(
        peer, stepTimeoutMillis = 100, buttonTimeoutMillis = 300, frameSpacingMillis = 0,
        randomBytes = { bytes -> if (random == null) bytes.fill(7) else random.copyInto(bytes) },
    )

    @Test fun `simulated scooter login proves self consistency only`() = runTest {
        val peer = SimulatedMiScooter()
        val result = session(peer).login(peer.token)
        assertTrue(result is MiLoginResult.Success)
        val success = result as MiLoginResult.Success
        assertFalse(success.keys.appKey.all { it == 0.toByte() })
        assertEquals("MiLoginResult.Success(<redacted>)", success.toString())
        success.keys.wipe()
        assertTrue(peer.borrowedWrites.all { bytes -> bytes.all { it == 0.toByte() } })
        assertTrue(peer.emitted.all { notification -> notification.copyBytes().all { it == 0.toByte() } })
    }

    @Test fun `simulated scooter registration token logs in for self consistency only`() = runTest {
        val peer = SimulatedMiScooter()
        val auth = session(peer)
        val result = auth.register(true) as MiRegistrationResult.Success
        assertEquals(12, result.token.size)
        assertEquals("MiRegistrationResult.Success(<redacted>)", result.toString())
        val login = auth.login(result.token) as MiLoginResult.Success
        login.keys.wipe()
        result.wipe()
        assertTrue(result.token.all { it == 0.toByte() })
        assertEquals(MiRegistrationProgress.IDLE, auth.registrationProgress.value)
    }

    @Test fun `registration without explicit consent performs no writes`() = runTest {
        val peer = SimulatedMiScooter()
        assertEquals(MiRegistrationResult.ConsentRequired, session(peer).register(false))
        assertTrue(peer.borrowedWrites.isEmpty())
    }

    @Test fun `wrong token gives proof mismatch before sending login proof`() = runTest {
        val peer = SimulatedMiScooter()
        assertEquals(MiLoginResult.ProofMismatch, session(peer).login(ByteArray(12)))
        assertFalse(peer.sentCommands.contains(0x0A))
    }

    @Test fun `invalid token is rejected without a write`() = runTest {
        val peer = SimulatedMiScooter()
        assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.INVALID_TOKEN), session(peer).login(ByteArray(11)))
        assertTrue(peer.borrowedWrites.isEmpty())
    }

    @Test fun `out of order notification is rejected instead of discarded`() = runTest {
        val peer = SimulatedMiScooter()
        peer.transform = { char, bytes -> char to if (bytes.contentEquals(byteArrayOf(0, 0, 1, 1))) {
            MiParcel.header(0, 1)
        } else bytes }
        assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.UNEXPECTED_RESPONSE), session(peer).login(peer.token))
    }

    @Test fun `matching ack on wrong characteristic is rejected`() = runTest {
        val peer = SimulatedMiScooter()
        peer.transform = { _, bytes -> MiAuthChar.UPNP to bytes }
        assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.UNEXPECTED_RESPONSE), session(peer).login(peer.token))
    }

    @Test fun `missing ack gives bounded timeout`() = runTest {
        val peer = SimulatedMiScooter()
        peer.transform = { _, _ -> null }
        assertEquals(MiLoginResult.Timeout, session(peer).login(peer.token))
    }

    @Test fun `button timeout is distinct from later registration timeout`() = runTest {
        val peer = SimulatedMiScooter()
        peer.transform = { char, bytes -> if (peer.registration && bytes.contentEquals(byteArrayOf(0, 0, 1, 1))) null
            else char to bytes }
        assertEquals(MiRegistrationResult.UserDidNotPressButton, session(peer).register(true))
    }

    @Test fun `link closes without waiting for timeout`() = runTest {
        val peer = SimulatedMiScooter()
        peer.closeFlow()
        assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.TRANSPORT_FAILURE), session(peer).login(peer.token))
        assertEquals(0, testScheduler.currentTime)
    }

    @Test fun `notification flow failure is sanitized without waiting for timeout`() = runTest {
        val peer = SimulatedMiScooter()
        peer.failFlow()
        assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.TRANSPORT_FAILURE), session(peer).login(peer.token))
        assertEquals(0, testScheduler.currentTime)
    }

    @Test fun `bounded write timeout wipes queued ack`() = runTest {
        val peer = SimulatedMiScooter().apply { blockAfterHeader = true }
        assertEquals(MiLoginResult.Timeout, session(peer).login(peer.token))
        assertTrue(peer.emitted.all { it.copyBytes().all { byte -> byte == 0.toByte() } })
    }

    @Test fun `registration cancellation clears progress and queued notification`() = runTest {
        val peer = SimulatedMiScooter().apply { blockAfterHeader = true }
        val auth = session(peer)
        val job = launch { auth.register(true) }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(MiRegistrationProgress.IDLE, auth.registrationProgress.value)
        assertTrue(peer.emitted.all { it.copyBytes().all { byte -> byte == 0.toByte() } })
        assertTrue(peer.borrowedWrites.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test fun `transport failure has sanitized reason`() = runTest {
        val peer = SimulatedMiScooter().apply { writeFailure = true }
        assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.TRANSPORT_FAILURE), session(peer).login(peer.token))
        assertEquals(MiRegistrationResult.ProtocolError(MiProtocolReason.TRANSPORT_FAILURE), session(peer).register(true))
    }

    @Test fun `short random parcel is rejected`() = runTest {
        val peer = SimulatedMiScooter()
        peer.transform = { char, bytes -> char to if (bytes.size == 18 && bytes[0] == 1.toByte()) bytes.copyOf(17) else bytes }
        assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.INVALID_RANDOM), session(peer).login(peer.token))
    }

    @Test fun `short proof parcel is rejected`() = runTest {
        val peer = SimulatedMiScooter()
        peer.transform = { char, bytes -> char to if (bytes.size == 16 && bytes[0] == 2.toByte()) bytes.copyOf(15) else bytes }
        assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.INVALID_PROOF), session(peer).login(peer.token))
    }

    @Test fun `truncated nonfinal parcel frame is rejected`() = runTest {
        val peer = SimulatedMiScooter()
        peer.transform = { char, bytes -> char to if (bytes.size == 20 && bytes[0] == 1.toByte()) bytes.copyOf(19) else bytes }
        assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.TRUNCATED_PARCEL), session(peer).login(peer.token))
    }

    @Test fun `unexpected chunk index is rejected`() = runTest {
        val peer = SimulatedMiScooter()
        peer.transform = { char, bytes -> char to bytes.copyOf().apply { if (size == 18) this[0] = 2 } }
        assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.OUT_OF_ORDER), session(peer).login(peer.token))
    }

    @Test fun `invalid remote info and unestablished DID length fail conservatively`() = runTest {
        val emptyDid = SimulatedMiScooter(0)
        assertEquals(MiRegistrationResult.ProtocolError(MiProtocolReason.INVALID_REMOTE_INFO), session(emptyDid).register(true))
        val shortDid = SimulatedMiScooter(5)
        assertEquals(MiRegistrationResult.ProtocolError(MiProtocolReason.UNSUPPORTED_DID_LENGTH), session(shortDid).register(true))
    }

    @Test fun `malformed peer public key is rejected`() = runTest {
        val peer = SimulatedMiScooter()
        var header = 0
        peer.transform = { char, bytes ->
            if (bytes.size == 6) header++
            char to bytes.copyOf().apply { if (header == 2 && size > 2 && this[0] != 0.toByte()) fill(0, 2) }
        }
        assertEquals(MiRegistrationResult.ProtocolError(MiProtocolReason.INVALID_PUBLIC_KEY), session(peer).register(true))
    }

    @Test fun `registration timeout after power readiness is ordinary timeout`() = runTest {
        val peer = SimulatedMiScooter()
        var silence = false
        peer.transform = { char, bytes ->
            if (bytes.contentEquals(byteArrayOf(0, 0, 1, 0))) silence = true
            if (silence) null else char to bytes
        }
        assertEquals(MiRegistrationResult.Timeout, session(peer).register(true))
    }

    @Test fun `auth error and login error are rejected`() = runTest {
        val peer = SimulatedMiScooter()
        peer.transform = { char, bytes -> char to bytes.copyOf().apply {
            if (size == 4 && this[0] == 0x21.toByte()) this[0] = 0x23
            if (size == 4 && this[0] == 0x11.toByte()) this[0] = 0x12
        } }
        assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.UNEXPECTED_RESPONSE), session(peer).login(peer.token))
        assertEquals(MiRegistrationResult.ProtocolError(MiProtocolReason.UNEXPECTED_RESPONSE), session(peer).register(true))
    }

    @Test fun `wrong status characteristic is rejected after otherwise valid login`() = runTest {
        val peer = SimulatedMiScooter()
        peer.transform = { char, bytes -> (if (bytes.size == 4 && bytes[0] == 0x21.toByte()) MiAuthChar.AVDTP else char) to bytes }
        assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.UNEXPECTED_RESPONSE), session(peer).login(peer.token))
    }

    @Test fun `hostile frame count is bounded before accepting parcel data`() = runTest {
        for (count in listOf(5, 255)) {
            val peer = SimulatedMiScooter()
            peer.transform = { char, bytes -> char to if (bytes.size == 6) MiParcel.header(0, count) else bytes }
            assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.PARCEL_TOO_LARGE), session(peer).login(peer.token))
        }
    }

    @Test fun `parcel on UPNP and corrupt parcel header are rejected`() = runTest {
        val wrongChannel = SimulatedMiScooter()
        wrongChannel.transform = { char, bytes -> (if (bytes.size == 6) MiAuthChar.UPNP else char) to bytes }
        assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.UNEXPECTED_RESPONSE), session(wrongChannel).login(wrongChannel.token))
        val corrupt = SimulatedMiScooter()
        corrupt.transform = { char, bytes -> char to if (bytes.size == 6) bytes.copyOf(5) else bytes }
        assertEquals(MiLoginResult.ProtocolError(MiProtocolReason.INVALID_HEADER), session(corrupt).login(corrupt.token))
    }

    @Test fun `missing parcel chunk gives timeout and wipes remaining notification`() = runTest {
        val peer = SimulatedMiScooter()
        peer.transform = { char, bytes -> if (bytes.size == 18) null else char to bytes }
        assertEquals(MiLoginResult.Timeout, session(peer).login(peer.token))
        assertTrue(peer.emitted.all { it.copyBytes().all { byte -> byte == 0.toByte() } })
    }

    @Test fun `invalid timings rejected`() {
        val peer = SimulatedMiScooter()
        assertThrows(IllegalArgumentException::class.java) { MiAuthSession(peer, stepTimeoutMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { MiAuthSession(peer, buttonTimeoutMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { MiAuthSession(peer, frameSpacingMillis = -1) }
    }

    @Test fun `cancellation wipes generated random queued notifications and borrowed writes`() = runTest {
        val peer = SimulatedMiScooter()
        val generated = CompletableDeferred<ByteArray>()
        peer.transform = { _, _ -> null }
        val auth = MiAuthSession(peer, randomBytes = { it.fill(42); generated.complete(it) })
        val job = launch { auth.login(peer.token) }
        val random = generated.await()
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(random.all { it == 0.toByte() })
        assertTrue(peer.borrowedWrites.all { bytes -> bytes.all { it == 0.toByte() } })
        assertEquals("MiAuthSession(<redacted>)", auth.toString())
    }
}
