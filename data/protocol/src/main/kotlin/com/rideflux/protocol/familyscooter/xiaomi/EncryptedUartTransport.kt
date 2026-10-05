package com.rideflux.protocol.familyscooter.xiaomi

import com.rideflux.domain.transport.BleTransport
import com.rideflux.protocol.familyscooter.RetailFraming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** L2 encrypted data plane. One collector owns framing; malformed traffic terminates it. */
class EncryptedUartTransport(private val delegate: BleTransport, keys: MiLoginKeys) : BleTransport {
    private val cipher = MiUartFrame(keys)
    private var closed = false
    override val incoming: Flow<ByteArray> = flow {
        // A wire frame is at most 255 + 16 bytes. The fixed buffer also bounds hostile input.
        val buffer = ByteArray(271)
        var count = 0
        try {
            delegate.incoming.collect { chunk ->
                try {
                    for (byte in chunk) {
                        buffer[count++] = byte
                        validatePrefix(buffer, count)
                        if (count >= 3 && count == (buffer[2].toInt() and 255) + 16) {
                            val encrypted = buffer.copyOf(count)
                            val message = try { cipher.decrypt(encrypted) } finally { encrypted.fill(0) }
                            try { emit(plainFrame(message)) } finally { message.fill(0) }
                            buffer.fill(0); count = 0
                        }
                    }
                } finally { chunk.fill(0) }
            }
            if (count != 0) throw MiFrameException(MiFrameError.MALFORMED)
        } finally { buffer.fill(0) }
    }

    override suspend fun connect() { check(!closed); delegate.connect() }
    override suspend fun disconnect() {
        if (closed) return
        closed = true
        cipher.close()
        delegate.disconnect()
    }
    override suspend fun write(bytes: ByteArray) {
        check(!closed)
        if (bytes.size < 8 || bytes[0] != 0x55.toByte() || bytes[1] != 0xAA.toByte() ||
            bytes.size != (bytes[2].toInt() and 255) + 6 || !RetailFraming.verify(bytes, 2)) {
            throw MiFrameException(MiFrameError.MALFORMED)
        }
        val message = bytes.copyOfRange(2, bytes.size - 2)
        val encrypted = try { cipher.encrypt(message) } finally { message.fill(0) }
        try { delegate.write(encrypted) } finally { encrypted.fill(0) }
    }
    override fun toString() = "EncryptedUartTransport(<redacted>)"
    private fun validatePrefix(buffer: ByteArray, count: Int) {
        if ((count == 1 && buffer[0] != 0x55.toByte()) ||
            (count == 2 && buffer[1] != 0xAB.toByte())) throw MiFrameException(MiFrameError.HEADER)
        if (count == 3 && (buffer[2].toInt() and 255) < 2) throw MiFrameException(MiFrameError.MALFORMED)
    }
    private fun plainFrame(message: ByteArray): ByteArray =
        RetailFraming.appendChecksum(byteArrayOf(0x55, 0xAA.toByte()) + message, message)
}
