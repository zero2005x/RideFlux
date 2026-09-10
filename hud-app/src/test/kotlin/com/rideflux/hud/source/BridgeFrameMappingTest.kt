package com.rideflux.hud.source

import com.rideflux.data.bridge.BridgeFrame
import com.rideflux.data.bridge.SignalLevel
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.hud.BridgeLinkState
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeFrameMappingTest {
    @Test fun preservesPhoneTripDuration() {
        val frame = BridgeFrame(100, 10f, 80f, null, null, null, 3600L, SignalLevel.GOOD, false, true)
        assertEquals(3600L, frame.toHudTelemetryFrame().tripDurationSeconds)
        assertEquals(null, frame.copy(tripDurationSeconds = null).toHudTelemetryFrame().tripDurationSeconds)
    }
    @Test fun notReady_mapsToConnecting() {
        val f = BridgeFrame(100, null, null, null, null, null, null, SignalLevel.NONE, true, false)
        val h = f.toHudTelemetryFrame()
        assertEquals(ConnectionState.Connecting, h.state)
        assertEquals(BridgeLinkState.PHONE_STANDBY, h.bridgeLinkState)
    }
    @Test fun readyGood_mapsToReady() {
        val f = BridgeFrame(100, 10f, 80f, null, null, null, null, SignalLevel.GOOD, false, true)
        val h = f.toHudTelemetryFrame()
        assertEquals(ConnectionState.Ready, h.state)
        assertEquals(BridgeLinkState.WHEEL_LIVE, h.bridgeLinkState)
    }
    @Test fun staleReady_mapsToConnecting() {
        val f = BridgeFrame(100, 10f, 80f, null, null, null, null, SignalLevel.GOOD, true, true)
        val h = f.toHudTelemetryFrame()
        assertEquals(ConnectionState.Connecting, h.state)
    }
    @Test fun signalMapping() {
        assertEquals(com.rideflux.hud.SignalQuality.GOOD, BridgeFrame(100, null, null, null, null, null, null, SignalLevel.GOOD, false, true).toHudTelemetryFrame().signal)
        assertEquals(com.rideflux.hud.SignalQuality.WEAK, BridgeFrame(100, null, null, null, null, null, null, SignalLevel.WEAK, false, true).toHudTelemetryFrame().signal)
        assertEquals(com.rideflux.hud.SignalQuality.NONE, BridgeFrame(100, null, null, null, null, null, null, SignalLevel.NONE, false, false).toHudTelemetryFrame().signal)
    }
}
