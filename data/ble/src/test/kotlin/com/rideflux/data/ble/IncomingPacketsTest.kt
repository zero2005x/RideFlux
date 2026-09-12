package com.rideflux.data.ble

import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class IncomingPacketsTest {
    @Test fun buffersEarlyRepliesInOrderAndCopiesCallbackMemory() = runTest {
        val packets = IncomingPackets()
        val bytes = byteArrayOf(1)
        packets.offer(bytes)
        bytes[0] = 2
        packets.offer(bytes)
        packets.close()
        assertEquals(listOf(1, 2), packets.flow.toList().map { it[0].toInt() })
    }

    @Test fun disconnectFailureReachesCollectorEvenBeforeItSubscribes() = runTest {
        val packets = IncomingPackets()
        val error = IOException("link lost")
        packets.close(error)
        // Coroutine stack-trace recovery rethrows a copy of the cause, so
        // match on type and message rather than instance identity.
        val thrown = runCatching { packets.flow.toList() }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertEquals(error.message, thrown!!.message)
    }

    @Test fun overflowFailsInsteadOfSilentlyDroppingAProtocolFragment() = runTest {
        val packets = IncomingPackets(capacity = 1)
        packets.offer(byteArrayOf(1))
        packets.offer(byteArrayOf(2))
        val error = runCatching { packets.flow.toList() }.exceptionOrNull()
        assertTrue(error is IOException)
    }
}
