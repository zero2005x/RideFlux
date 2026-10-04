package com.rideflux.protocol.familyscooter.ninebot

/** Reassembles 5A A5 frames from arbitrary ordered BLE notification chunks. */
class NinebotRetailFrameAssembler {
    private val pending = ArrayList<Byte>()

    fun append(chunk: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        for (byte in chunk) {
            pending.add(byte)
            // A single retail frame is at most 255 payload bytes + 9 overhead.
            if (pending.size > MAX_BUFFER_SIZE) pending.removeAt(0)
            while (true) {
                while (pending.isNotEmpty() && (pending[0].toInt() and 0xff) != 0x5a) pending.removeAt(0)
                if (pending.size < 2) break
                if ((pending[1].toInt() and 0xff) != 0xa5) {
                    pending.removeAt(0)
                    continue
                }
                if (pending.size < 3) break
                val frameLength = (pending[2].toInt() and 0xff) + 9
                if (pending.size < frameLength) break
                val candidate = pending.subList(0, frameLength).toByteArray()
                if (NinebotRetailCodec.decodeFrame(candidate) == null) {
                    pending.removeAt(0)
                    continue
                }
                frames += candidate
                pending.subList(0, frameLength).clear()
            }
        }
        return frames
    }

    fun reset() = pending.clear()

    private companion object { const val MAX_BUFFER_SIZE = 528 }
}
