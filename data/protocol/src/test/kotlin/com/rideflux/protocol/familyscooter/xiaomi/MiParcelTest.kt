package com.rideflux.protocol.familyscooter.xiaomi

import org.junit.Assert.*
import org.junit.Test

class MiParcelTest {
    @Test fun `chunks at MTU boundary and zero round trip`() {
        for (size in listOf(0, 1, 17, 18, 19, 64, MiParcel.MAX_BYTES)) {
            val input = ByteArray(size) { it.toByte() }
            val frames = MiParcel.fragment(input)
            assertEquals((size + 17) / 18, frames.size)
            assertArrayEquals(input, MiParcel.reassemble(frames, frames.size))
            assertEquals(frames.size, MiParcel.frameCount(MiParcel.header(3, frames.size)))
        }
    }

    @Test fun `malformed input has documented non secret errors`() {
        assertReason(MiProtocolReason.INVALID_HEADER) { MiParcel.frameCount(byteArrayOf(0)) }
        for (offset in 0..2) {
            assertReason(MiProtocolReason.INVALID_HEADER) { MiParcel.frameCount(ByteArray(6).apply { this[offset] = 1 }) }
        }
        assertReason(MiProtocolReason.PARCEL_TOO_LARGE) { MiParcel.frameCount(byteArrayOf(0, 0, 0, 0, 0, 1)) }
        assertReason(MiProtocolReason.INVALID_FRAME_COUNT) { MiParcel.reassemble(emptyList(), 1) }
        assertReason(MiProtocolReason.INVALID_FRAME) { MiParcel.reassemble(listOf(byteArrayOf(1, 0)), 1) }
        assertReason(MiProtocolReason.INVALID_FRAME) { MiParcel.reassemble(listOf(ByteArray(21)), 1) }
        assertReason(MiProtocolReason.INVALID_FRAME) { MiParcel.reassemble(listOf(byteArrayOf(1, 1, 4)), 1) }
        assertReason(MiProtocolReason.OUT_OF_ORDER) { MiParcel.reassemble(listOf(byteArrayOf(2, 0, 4)), 1) }
        assertReason(MiProtocolReason.TRUNCATED_PARCEL) { MiParcel.reassemble(listOf(byteArrayOf(1, 0, 4), byteArrayOf(2, 0, 4)), 2) }
        assertThrows(IllegalArgumentException::class.java) { MiParcel.header(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { MiParcel.header(256, 1) }
        assertThrows(IllegalArgumentException::class.java) { MiParcel.header(1, -1) }
        assertThrows(IllegalArgumentException::class.java) { MiParcel.header(1, 256) }
        assertReason(MiProtocolReason.INVALID_FRAME_COUNT) { MiParcel.reassemble(emptyList(), -1) }
        assertReason(MiProtocolReason.INVALID_FRAME_COUNT) { MiParcel.reassemble(emptyList(), 256) }
        assertThrows(IllegalArgumentException::class.java) { MiParcel.fragment(ByteArray(MiParcel.MAX_BYTES + 1)) }
    }

    @Test fun `notification owns its copy and hides secrets`() {
        val secret = byteArrayOf(42, 43, 44)
        val notification = com.rideflux.domain.transport.MiAuthNotification(com.rideflux.domain.transport.MiAuthChar.AVDTP, secret)
        secret.fill(0)
        assertArrayEquals(byteArrayOf(42, 43, 44), notification.copyBytes())
        assertFalse(notification.toString().contains("42"))
        notification.wipe()
        assertArrayEquals(ByteArray(3), notification.copyBytes())
    }

    private fun assertReason(reason: MiProtocolReason, block: () -> Unit) {
        val error = assertThrows(MiProtocolException::class.java, block)
        assertEquals(reason, error.reason)
    }
}
