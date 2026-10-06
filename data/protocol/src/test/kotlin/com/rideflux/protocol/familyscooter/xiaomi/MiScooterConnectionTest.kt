/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyscooter.xiaomi

import com.rideflux.domain.bond.BondEntry
import com.rideflux.domain.bond.BondFamily
import com.rideflux.domain.bond.BondStore
import com.rideflux.domain.command.CommandOutcome
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.connection.MiRegistrationState
import com.rideflux.domain.connection.ScooterHandshakeState
import com.rideflux.domain.device.ScooterDevice
import com.rideflux.domain.transport.MiAuthChar
import com.rideflux.domain.transport.MiAuthNotification
import com.rideflux.domain.transport.MiAuthTransport
import java.security.interfaces.ECPublicKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MiScooterConnectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val device = ScooterDevice("AA:BB:CC:DD:EE:FF", "MIScooter Pro")
    private lateinit var store: InMemoryBondStore
    private lateinit var transport: SimulatedScooterTransport

    private class InMemoryBondStore : BondStore {
        val entries = mutableMapOf<String, BondEntry>()
        override suspend fun list(): List<BondEntry> = entries.values.map {
            BondEntry(it.mac, it.family, it.credential().copyOf(), it.label, it.model)
        }
        override suspend fun put(entry: BondEntry) {
            entries[entry.mac] = BondEntry(entry.mac, entry.family, entry.credential().copyOf(), entry.label, entry.model)
        }
        override suspend fun remove(mac: String): Boolean = entries.remove(mac) != null
    }

    private class SimulatedScooterTransport(val expectedToken: ByteArray) : MiAuthTransport {
        private val notifications = Channel<MiAuthNotification>(Channel.UNLIMITED)
        private val dataChannel = Channel<ByteArray>(Channel.UNLIMITED)
        override val authNotifications: Flow<MiAuthNotification> = notifications.receiveAsFlow()
        override val incoming: Flow<ByteArray> = dataChannel.receiveAsFlow()
        var connected = false
        var disconnected = false
        var loginKeys: MiLoginKeys? = null
        var scooterCipher: MiUartFrame? = null

        private val scooterRandom = ByteArray(16) { (it + 5).toByte() }
        private val ecdhPair = MiEcdh.generate()
        private val did = ByteArray(20) { (it + 40).toByte() }
        private var setupKeys: MiSetupKeys? = null
        private var command = 0
        private var expectedFrames = 0
        private val parcelChunks = mutableListOf<ByteArray>()
        private val parcels = ArrayDeque<ByteArray>()

        override suspend fun connect() { connected = true }
        override suspend fun disconnect() {
            disconnected = true
            notifications.close()
            dataChannel.close()
        }

        override suspend fun write(bytes: ByteArray) {
            val cipher = scooterCipher ?: return
            val plain = try { cipher.decrypt(bytes) } catch (_: Exception) { return }
            if (plain.size >= 4 && plain[2] == 0x01.toByte()) {
                val reg = plain[3].toInt() and 0xFF
                if (reg == 0xB0) {
                    val b0Payload = ByteArray(32) { 0 }
                    b0Payload[24] = 0xA0.toByte(); b0Payload[25] = 0x0F.toByte()
                    val replyFrame = byteArrayOf(0x22.toByte(), 0x23.toByte(), 0x01.toByte(), 0xB0.toByte()) + b0Payload
                    val encrypted = cipher.encrypt(replyFrame)
                    dataChannel.trySend(encrypted)
                }
            }
        }

        override suspend fun writeAuth(characteristic: MiAuthChar, bytes: ByteArray) {
            if (characteristic == MiAuthChar.UPNP) {
                when (bytes[0].toInt() and 255) {
                    0x13 -> response(0x11)
                    0xA2 -> queueParcel(ByteArray(did.size + 4).also { did.copyInto(it, 4) })
                }
            } else when {
                bytes.contentEquals(byteArrayOf(0, 0, 1, 1)) -> {
                    MiParcel.fragment(parcels.first()).forEach {
                        notifications.trySend(MiAuthNotification(MiAuthChar.AVDTP, it))
                    }
                }
                bytes.contentEquals(byteArrayOf(0, 0, 1, 0)) -> {
                    parcels.removeFirst()
                    if (parcels.isNotEmpty()) {
                        emitHeader(MiParcel.fragment(parcels.first()).size)
                    }
                }
                bytes.size == 6 && bytes[0] == 0.toByte() -> {
                    command = bytes[3].toInt() and 255
                    expectedFrames = MiParcel.frameCount(bytes)
                    parcelChunks.clear()
                    response(0x101)
                }
                else -> {
                    parcelChunks.add(bytes.copyOf())
                    if (parcelChunks.size == expectedFrames) finishPayload()
                }
            }
        }

        private fun finishPayload() {
            val payload = MiParcel.reassemble(parcelChunks, expectedFrames)
            response(0x100)
            when (command) {
                0x0B -> {
                    val derived = MiKeys.login(expectedToken, payload, scooterRandom)
                    loginKeys = derived
                    scooterCipher = MiUartFrame(
                        MiLoginKeys(
                            derived.appKey.copyOf(), derived.devKey.copyOf(), derived.appIv.copyOf(), derived.devIv.copyOf(),
                            derived.loginInfo.copyOf(), derived.scooterProof.copyOf(),
                        ),
                    )
                    queueParcel(scooterRandom.copyOf())
                    queueParcel(derived.scooterProof.copyOf())
                }
                0x0A -> {
                    response(0x21)
                }
                0x03 -> {
                    val secret = MiEcdh.sharedSecret(ecdhPair.private, payload)
                    setupKeys = MiKeys.setup(secret)
                    secret.fill(0)
                    setupKeys!!.token.copyInto(expectedToken)
                    queueParcel(MiEcdh.publicRaw(ecdhPair.public as ECPublicKey))
                }
                0x00 -> Unit
            }
        }

        private fun queueParcel(payload: ByteArray) {
            parcels.add(payload)
            if (parcels.size == 1) emitHeader(MiParcel.fragment(payload).size)
        }

        private fun emitHeader(frames: Int) {
            notifications.trySend(MiAuthNotification(MiAuthChar.AVDTP, byteArrayOf(0, 0, 0, 0, frames.toByte(), 0)))
        }

        private fun response(opcode: Int) {
            if (opcode >= 0x100) {
                notifications.trySend(MiAuthNotification(MiAuthChar.AVDTP, byteArrayOf(0, 0, 1, (opcode - 0x100).toByte())))
            } else {
                notifications.trySend(MiAuthNotification(MiAuthChar.UPNP, byteArrayOf(opcode.toByte(), 0, 0, 0)))
            }
        }
    }

    @Before
    fun setUp() {
        store = InMemoryBondStore()
        val token = ByteArray(12) { (it + 1).toByte() }
        transport = SimulatedScooterTransport(token)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun startWithStoredTokenLogsInAndReachesReady() {
        runBlocking {
            store.put(BondEntry(device.address, BondFamily.XIAOMI_MI, transport.expectedToken, "MyScooter", "M365"))
            val connection = MiScooterConnection(transport, device, scope, store)

            connection.start()

            assertEquals(ConnectionState.Ready, connection.state.value)
            assertEquals(ScooterHandshakeState.READY_FOR_TELEMETRY, connection.handshakeState.value)
            assertEquals(MiRegistrationState.NOT_REQUIRED, connection.registrationState.value)
            assertFalse(connection.lockSupported)

            connection.close()
            assertEquals(ConnectionState.Disconnected, connection.state.value)
        }
    }

    @Test
    fun startWithoutStoredTokenRequiresUserConsent() {
        runBlocking {
            val connection = MiScooterConnection(transport, device, scope, store)

            connection.start()

            assertEquals(ConnectionState.ScooterHandshaking, connection.state.value)
            assertEquals(MiRegistrationState.CONSENT_REQUIRED, connection.registrationState.value)

            val rejected = connection.registerAfterUserConfirmation(false)
            assertFalse(rejected)
            assertEquals(MiRegistrationState.CONSENT_REQUIRED, connection.registrationState.value)

            connection.close()
            assertEquals(ConnectionState.Disconnected, connection.state.value)
        }
    }

    @Test
    fun registerAfterUserConfirmationCompletesAndSavesToken() {
        runBlocking {
            val connection = MiScooterConnection(transport, device, scope, store)
            connection.start()
            assertEquals(MiRegistrationState.CONSENT_REQUIRED, connection.registrationState.value)

            val success = connection.registerAfterUserConfirmation(true)
            assertTrue(success)
            assertEquals(ConnectionState.Ready, connection.state.value)

            val entry = store.list().firstOrNull { it.mac == BondEntry.normalizeMac(device.address) }
            assertNotNull(entry)
            assertEquals(BondFamily.XIAOMI_MI, entry?.family)
            assertEquals(12, entry?.credential()?.size)

            connection.close()
        }
    }

    @Test
    fun lockAndUnlockAreUnsupported() {
        runBlocking {
            val connection = MiScooterConnection(transport, device, scope, store)
            val lockOutcome = connection.lock()
            val unlockOutcome = connection.unlock()

            assertTrue(lockOutcome is CommandOutcome.Unsupported)
            assertTrue(unlockOutcome is CommandOutcome.Unsupported)
        }
    }

    @Test
    fun readRegisterReturnsNullWhenNotReady() {
        runBlocking {
            val connection = MiScooterConnection(transport, device, scope, store)
            val reply = connection.readRegister(0xB0, 32)
            assertNull(reply)
        }
    }

    @Test
    fun readRegisterSucceedsWhenReadyAndUpdatesTelemetry() {
        runBlocking {
            store.put(BondEntry(device.address, BondFamily.XIAOMI_MI, transport.expectedToken, "MyScooter", "M365"))
            val connection = MiScooterConnection(transport, device, scope, store, readTimeoutMillis = 1_000)
            connection.start()
            assertEquals(ConnectionState.Ready, connection.state.value)

            val reply = connection.readRegister(0xB0, 32)
            assertNotNull(reply)
            assertEquals(32, reply?.size)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { connection.readRegister(-1, 32) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { connection.readRegister(0xB0, 0) }
            }

            connection.close()
            assertEquals(ConnectionState.Disconnected, connection.state.value)
            connection.close()
        }
    }

    @Test
    fun startFailsWhenTransportConnectThrows() {
        runBlocking {
            val brokenTransport = object : MiAuthTransport by transport {
                override suspend fun connect() { throw java.io.IOException("boom") }
            }
            val connection = MiScooterConnection(brokenTransport, device, scope, store)
            connection.start()
            assertTrue(connection.state.value is ConnectionState.Failed)
        }
    }

    @Test
    fun registerRejectedWhenNotConsentRequired() {
        runBlocking {
            val connection = MiScooterConnection(transport, device, scope, store)
            val registered = connection.registerAfterUserConfirmation(true)
            assertFalse(registered)
        }
    }

    @Test
    fun toStringRedactsSecrets() {
        val connection = MiScooterConnection(transport, device, scope, store)
        assertEquals("MiScooterConnection(<redacted>)", connection.toString())
    }
}
