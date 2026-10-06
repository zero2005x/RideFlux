package com.rideflux.protocol.familyscooter.xiaomi

import com.rideflux.domain.transport.MiAuthChar
import com.rideflux.domain.transport.MiAuthNotification
import com.rideflux.domain.transport.MiAuthTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** One collector for a whole auth operation: notifications received during writes remain ordered. */
internal class MiAuthMailbox(
    scope: CoroutineScope,
    private val transport: MiAuthTransport,
    private val timeoutMillis: Long,
    private val spacingMillis: Long,
) {
    private val queue = Channel<MiAuthNotification>(64, onUndeliveredElement = { it.wipe() })
    private val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            transport.authNotifications.collect { notification ->
                try { queue.send(notification) } catch (error: Throwable) {
                    notification.wipe()
                    throw error
                }
            }
            queue.close()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { queue.close(MiProtocolException(MiProtocolReason.TRANSPORT_FAILURE)) }
    }

    suspend fun write(characteristic: MiAuthChar, bytes: ByteArray) {
        try { withTimeout(timeoutMillis) { transport.writeAuth(characteristic, bytes) } }
        finally { bytes.fill(0) }
    }

    suspend fun expect(opcode: Int, timeout: Long = timeoutMillis) {
        val notification = withTimeout(timeout) { queue.receive() }
        val bytes = notification.copyBytes()
        try {
            val characteristic = if (opcode >= 0x100) MiAuthChar.AVDTP else MiAuthChar.UPNP
            if (notification.characteristic != characteristic) {
                throw MiProtocolException(MiProtocolReason.UNEXPECTED_RESPONSE)
            }
            val expected = when (opcode) {
                0x100 -> byteArrayOf(0, 0, 1, 0)
                0x101 -> byteArrayOf(0, 0, 1, 1)
                else -> byteArrayOf(opcode.toByte(), 0, 0, 0)
            }
            if (!bytes.contentEquals(expected)) throw MiProtocolException(MiProtocolReason.UNEXPECTED_RESPONSE)
        } finally { bytes.fill(0); notification.wipe() }
    }

    suspend fun send(
        command: Int, payload: ByteArray, readyTimeout: Long = timeoutMillis,
        onReadyWait: () -> Unit = {}, onReady: () -> Unit = {},
    ) {
        val frames = MiParcel.fragment(payload)
        try {
            write(MiAuthChar.AVDTP, MiParcel.header(command, frames.size))
            onReadyWait()
            expect(0x101, readyTimeout)
            onReady()
            frames.forEachIndexed { index, frame ->
                write(MiAuthChar.AVDTP, frame)
                if (index < frames.lastIndex) delay(spacingMillis)
            }
            expect(0x100)
        } finally { frames.forEach { it.fill(0) } }
    }

    suspend fun receive(): ByteArray = withTimeout(timeoutMillis) { receiveParcel() }

    private suspend fun receiveParcel(): ByteArray {
        val header = receiveAvdtp()
        val count = try { MiParcel.frameCount(header) } finally { header.fill(0) }
        if (count > 4) throw MiProtocolException(MiProtocolReason.PARCEL_TOO_LARGE)
        val frames = mutableListOf<ByteArray>()
        try {
            write(MiAuthChar.AVDTP, byteArrayOf(0, 0, 1, 1))
            repeat(count) { index ->
                val frame = receiveAvdtp()
                frames.add(frame)
                MiParcel.validateFrame(frame, index + 1, index == count - 1)
            }
            val result = MiParcel.reassemble(frames, count)
            if (result.size > 64) {
                result.fill(0)
                throw MiProtocolException(MiProtocolReason.PARCEL_TOO_LARGE)
            }
            try { write(MiAuthChar.AVDTP, byteArrayOf(0, 0, 1, 0)) }
            catch (error: Throwable) { result.fill(0); throw error }
            return result
        } finally { frames.forEach { it.fill(0) } }
    }

    private suspend fun receiveAvdtp(): ByteArray {
        val notification = withTimeout(timeoutMillis) { queue.receive() }
        try {
            if (notification.characteristic != MiAuthChar.AVDTP) {
                throw MiProtocolException(MiProtocolReason.UNEXPECTED_RESPONSE)
            }
            return notification.copyBytes()
        } finally { notification.wipe() }
    }

    suspend fun close() {
        collector.cancel()
        collector.join()
        while (true) {
            val notification = queue.tryReceive().getOrNull() ?: break
            notification.wipe()
        }
        queue.cancel()
    }
}
