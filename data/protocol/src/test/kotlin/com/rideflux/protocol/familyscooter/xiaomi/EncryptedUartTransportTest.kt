/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyscooter.xiaomi

import com.rideflux.domain.transport.BleTransport
import com.rideflux.protocol.familyscooter.RetailFraming
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EncryptedUartTransportTest {
    private val token = ByteArray(12) { (it + 1).toByte() }
    private val appRandom = ByteArray(16) { (it + 20).toByte() }
    private val scooterRandom = ByteArray(16) { (it + 40).toByte() }
    private lateinit var keys: MiLoginKeys
    private lateinit var delegate: TestBleTransport
    private lateinit var transport: EncryptedUartTransport

    private class TestBleTransport : BleTransport {
        val written = mutableListOf<ByteArray>()
        private val channel = Channel<ByteArray>(Channel.UNLIMITED)
        override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
        var connected = false
        var disconnected = false

        override suspend fun connect() { connected = true }
        override suspend fun disconnect() { disconnected = true; channel.close() }
        override suspend fun write(bytes: ByteArray) { written.add(bytes.copyOf()) }
        fun emitIncoming(bytes: ByteArray) { channel.trySend(bytes.copyOf()) }
        fun closeIncoming(cause: Throwable? = null) { channel.close(cause) }
    }

    @Before
    fun setUp() {
        keys = MiKeys.login(token, appRandom, scooterRandom)
        delegate = TestBleTransport()
        transport = EncryptedUartTransport(delegate, keys)
    }

    @Test
    fun writeValidM365FrameEncryptsAndForwardsToDelegate() {
        runBlocking {
            // Plain M365 frame: 55 AA 03 20 01 B0 payload... + 2 bytes checksum
            val payload = byteArrayOf(0x03, 0x20, 0x01, 0xB0.toByte(), 0x20)
            val plain = RetailFraming.appendChecksum(byteArrayOf(0x55, 0xAA.toByte()) + payload, payload)

            transport.write(plain)

            assertEquals(1, delegate.written.size)
            val encrypted = delegate.written.first()
            assertEquals(0x55.toByte(), encrypted[0])
            assertEquals(0xAB.toByte(), encrypted[1])
            assertEquals(payload[0], encrypted[2]) // size byte sent in clear
            assertEquals((payload[0].toInt() and 255) + 16, encrypted.size)
        }
    }

    @Test
    fun writeRejectsMalformedFrames() {
        runBlocking {
            // Too short (< 8)
            assertThrows(MiFrameException::class.java) {
                runBlocking { transport.write(byteArrayOf(0x55, 0xAA.toByte(), 0x01, 0x02)) }
            }

            // Wrong magic
            assertThrows(MiFrameException::class.java) {
                runBlocking { transport.write(ByteArray(10) { 0 }) }
            }

            // Wrong length in byte 2
            val payload = byteArrayOf(0x03, 0x20, 0x01, 0xB0.toByte(), 0x20)
            val plain = RetailFraming.appendChecksum(byteArrayOf(0x55, 0xAA.toByte()) + payload, payload)
            val badLen = plain.copyOf().also { it[2] = 0x10 }
            assertThrows(MiFrameException::class.java) {
                runBlocking { transport.write(badLen) }
            }

            // Wrong checksum
            val badCrc = plain.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
            assertThrows(MiFrameException::class.java) {
                runBlocking { transport.write(badCrc) }
            }

            // Closed transport
            transport.disconnect()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { transport.write(plain) }
            }
        }
    }

    @Test
    fun incomingDecryptsValid55ABFrame() {
        runBlocking {
            // Construct a valid encrypted frame from the scooter side using devKey and devIv
            val scooterKeys = MiLoginKeys(
                keys.appKey.copyOf(), keys.devKey.copyOf(), keys.appIv.copyOf(), keys.devIv.copyOf(),
                keys.loginInfo.copyOf(), keys.scooterProof.copyOf(),
            )
            val scooterCipher = MiUartFrame(scooterKeys)
            val scooterPayload = byteArrayOf(0x02, 0x20, 0x01, 0xB0.toByte())
            val encrypted = scooterCipher.encrypt(scooterPayload)

            delegate.emitIncoming(encrypted)

            val decrypted = transport.incoming.first()
            assertEquals(0x55.toByte(), decrypted[0])
            assertEquals(0xAA.toByte(), decrypted[1])
            assertTrue(RetailFraming.verify(decrypted, 2))
            val body = decrypted.copyOfRange(2, decrypted.size - 2)
            assertArrayEquals(scooterPayload, body)
        }
    }

    @Test
    fun incomingReassemblesFragmented55ABFrames() {
        runBlocking {
            val scooterKeys = MiLoginKeys(
                keys.appKey.copyOf(), keys.devKey.copyOf(), keys.appIv.copyOf(), keys.devIv.copyOf(),
                keys.loginInfo.copyOf(), keys.scooterProof.copyOf(),
            )
            val scooterCipher = MiUartFrame(scooterKeys)
            val scooterPayload = byteArrayOf(0x04, 0x20, 0x01, 0xB0.toByte(), 0x10, 0x20)
            val encrypted = scooterCipher.encrypt(scooterPayload)

            // Split into chunks across notifications
            val chunk1 = encrypted.copyOfRange(0, 5)
            val chunk2 = encrypted.copyOfRange(5, encrypted.size)
            delegate.emitIncoming(chunk1)
            delegate.emitIncoming(chunk2)

            val decrypted = transport.incoming.first()
            assertTrue(RetailFraming.verify(decrypted, 2))
            val body = decrypted.copyOfRange(2, decrypted.size - 2)
            assertArrayEquals(scooterPayload, body)
        }
    }

    @Test
    fun incomingRejectsBadHeader() {
        runBlocking {
            delegate.emitIncoming(byteArrayOf(0x55, 0xAC.toByte(), 0x05))
            val err = assertThrows(MiFrameException::class.java) {
                runBlocking { transport.incoming.toList() }
            }
            assertEquals(MiFrameError.HEADER, err.reason)
        }
    }

    @Test
    fun incomingRejectsInvalidLength() {
        runBlocking {
            delegate.emitIncoming(byteArrayOf(0x55, 0xAB.toByte(), 0x01))
            val err = assertThrows(MiFrameException::class.java) {
                runBlocking { transport.incoming.toList() }
            }
            assertEquals(MiFrameError.MALFORMED, err.reason)
        }
    }

    @Test
    fun connectAndDisconnectLifecycle() {
        runBlocking {
            assertFalse(delegate.connected)
            transport.connect()
            assertTrue(delegate.connected)

            assertFalse(delegate.disconnected)
            transport.disconnect()
            assertTrue(delegate.disconnected)
            // Idempotent close
            transport.disconnect()

            assertThrows(IllegalStateException::class.java) {
                runBlocking { transport.connect() }
            }
        }
    }

    @Test
    fun incomingRejectsBadFirstByte() {
        runBlocking {
            delegate.emitIncoming(byteArrayOf(0x56))
            val err = assertThrows(MiFrameException::class.java) {
                runBlocking { transport.incoming.toList() }
            }
            assertEquals(MiFrameError.HEADER, err.reason)
        }
    }

    @Test
    fun incomingRejectsTrailingIncompleteBytesOnClose() {
        runBlocking {
            delegate.emitIncoming(byteArrayOf(0x55, 0xAB.toByte(), 0x05))
            delegate.closeIncoming()
            val err = assertThrows(MiFrameException::class.java) {
                runBlocking { transport.incoming.toList() }
            }
            assertEquals(MiFrameError.MALFORMED, err.reason)
        }
    }

    @Test
    fun toStringRedactsSecrets() {
        assertEquals("EncryptedUartTransport(<redacted>)", transport.toString())
    }
}
