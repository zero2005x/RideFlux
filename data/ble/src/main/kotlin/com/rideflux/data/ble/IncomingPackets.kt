package com.rideflux.data.ble

import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** One connection owns the receive stream. Preserve packet order and terminal failures. */
internal class IncomingPackets(capacity: Int = 256) {
    private val packets = Channel<ByteArray>(capacity)
    val flow = packets.receiveAsFlow()

    fun offer(bytes: ByteArray) {
        val result = packets.trySend(bytes.copyOf())
        if (result.isFailure && !result.isClosed) {
            // Dropping an arbitrary BLE fragment corrupts the protocol byte stream.
            packets.close(IOException("BLE receive buffer overflow"))
        }
    }

    fun close(cause: Throwable? = null) { packets.close(cause) }
}
