package com.rideflux.data.ble

import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.wheel.WheelFamily
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class WheelRepositoryLifecycleTest {
    @Test fun releasingFailedSessionCannotCloseItsReplacement() = runTest {
        val transports = mutableListOf<FakeBleTransport>()
        val repository = WheelRepositoryImpl(backgroundScope) { _, _, scope ->
            val transport = FakeBleTransport().also(transports::add)
            WheelConnectionImpl(transport, FakeWheelCodec(), scope, clock = { 1_000L })
        }
        val oldDashboard = repository.connect("wheel", WheelFamily.G)
        val oldRecording = repository.connect("wheel", WheelFamily.G)
        runCurrent()
        assertEquals(1, transports.size)
        transports.first().emitFailure(IOException("link lost"))
        runCurrent()
        assertTrue(oldDashboard.state.value is ConnectionState.Failed)

        val replacement = repository.connect("wheel", WheelFamily.G)
        runCurrent()
        assertEquals(2, transports.size)
        oldDashboard.close()
        oldRecording.close()
        oldDashboard.close()
        assertEquals(0, transports.last().disconnectCount)
        assertTrue(repository.activeConnections().first().containsKey("wheel"))

        replacement.close()
        assertEquals(1, transports.last().disconnectCount)
        assertTrue(repository.activeConnections().first().isEmpty())
    }

    @Test fun repeatedCloseDoesNotReleaseAnotherCurrentOwner() = runTest {
        val transport = FakeBleTransport()
        val repository = WheelRepositoryImpl(backgroundScope) { _, _, scope ->
            WheelConnectionImpl(transport, FakeWheelCodec(), scope, clock = { 1_000L })
        }
        val first = repository.connect("wheel", WheelFamily.G)
        val second = repository.connect("wheel", WheelFamily.G)
        runCurrent()
        first.close()
        first.close()
        assertEquals(0, transport.disconnectCount)
        second.close()
        assertEquals(1, transport.disconnectCount)
    }
}
