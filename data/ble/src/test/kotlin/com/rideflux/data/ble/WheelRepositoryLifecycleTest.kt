/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.codec.WheelCodec
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

    @Test fun reconnectWithDifferentExpectedFamilyEvictsAndRebuilds() = runTest {
        val transports = mutableListOf<FakeBleTransport>()
        val codecs = mutableListOf<WheelCodec>()
        val repository = WheelRepositoryImpl(backgroundScope) { _, family, scope ->
            val transport = FakeBleTransport().also(transports::add)
            val codec = FakeWheelCodec(family = family ?: WheelFamily.G).also(codecs::add)
            WheelConnectionImpl(transport, codec, scope, clock = { 1_000L })
        }
        val first = repository.connect("wheel", WheelFamily.G)
        runCurrent()
        assertEquals(1, transports.size)
        assertEquals(WheelFamily.G, codecs.first().family)

        // Connect with WheelFamily.V (e.g. user corrected brand)
        val second = repository.connect("wheel", WheelFamily.V)
        runCurrent()
        assertEquals(2, transports.size)
        assertEquals(WheelFamily.V, codecs.last().family)
        assertEquals(1, transports.first().disconnectCount)

        first.close()
        second.close()
    }
}
