package com.rideflux.protocol.familyscooter.xiaomi

/** MTU 23 framing, implemented from L2 notes; not tried on a real scooter. */
object MiParcel {
    const val PAYLOAD_SIZE = 18
    const val MAX_FRAMES = 255
    const val MAX_BYTES = PAYLOAD_SIZE * MAX_FRAMES

    /** Returned buffers belong to the caller, who must wipe them after writing. */
    fun fragment(payload: ByteArray): List<ByteArray> {
        require(payload.size <= MAX_BYTES) { "Parcel exceeds index limit" }
        return List((payload.size + PAYLOAD_SIZE - 1) / PAYLOAD_SIZE) { index ->
            val start = index * PAYLOAD_SIZE
            val end = minOf(start + PAYLOAD_SIZE, payload.size)
            ByteArray(end - start + 2).apply {
                this[0] = (index + 1).toByte()
                payload.copyInto(this, 2, start, end)
            }
        }
    }

    fun header(command: Int, frames: Int): ByteArray {
        require(command in 0..255 && frames in 0..MAX_FRAMES) { "Invalid parcel header" }
        return byteArrayOf(0, 0, 0, command.toByte(), frames.toByte(), 0)
    }

    fun frameCount(header: ByteArray): Int {
        if (header.size != 6 || header[0] != 0.toByte() || header[1] != 0.toByte() || header[2] != 0.toByte()) {
            throw MiProtocolException(MiProtocolReason.INVALID_HEADER)
        }
        val count = (header[4].toInt() and 255) or ((header[5].toInt() and 255) shl 8)
        if (count > MAX_FRAMES) throw MiProtocolException(MiProtocolReason.PARCEL_TOO_LARGE)
        return count
    }

    /** Strict order prevents duplicate/missing chunks from being accepted as complete. */
    fun reassemble(frames: List<ByteArray>, expectedCount: Int): ByteArray {
        if (expectedCount !in 0..MAX_FRAMES || frames.size != expectedCount) {
            throw MiProtocolException(MiProtocolReason.INVALID_FRAME_COUNT)
        }
        var size = 0
        frames.forEachIndexed { index, frame ->
            validateFrame(frame, index + 1, index == expectedCount - 1)
            size += frame.size - 2
        }
        val output = ByteArray(size)
        var offset = 0
        frames.forEach { frame ->
            frame.copyInto(output, offset, 2)
            offset += frame.size - 2
        }
        return output
    }

    internal fun validateFrame(frame: ByteArray, index: Int, last: Boolean) {
        if (frame.size !in 3..20 || frame[1] != 0.toByte()) {
            throw MiProtocolException(MiProtocolReason.INVALID_FRAME)
        }
        if ((frame[0].toInt() and 255) != index) throw MiProtocolException(MiProtocolReason.OUT_OF_ORDER)
        if (!last && frame.size != 20) throw MiProtocolException(MiProtocolReason.TRUNCATED_PARCEL)
    }
}

enum class MiProtocolReason {
    INVALID_HEADER, PARCEL_TOO_LARGE, INVALID_FRAME_COUNT, INVALID_FRAME, OUT_OF_ORDER,
    TRUNCATED_PARCEL, UNEXPECTED_RESPONSE, INVALID_TOKEN, INVALID_RANDOM, INVALID_PROOF,
    INVALID_REMOTE_INFO, INVALID_PUBLIC_KEY, UNSUPPORTED_DID_LENGTH, TRANSPORT_FAILURE,
}

/** Only an enum reason is retained; peer bytes and transport exception messages are never exposed. */
class MiProtocolException(val reason: MiProtocolReason) : Exception("Mi protocol error: $reason")
