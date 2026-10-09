/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import com.rideflux.domain.connection.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the wording of the "leaving RELAYING" diagnostic line that the 12:50 analysis relies on. */
class RelayingExitReasonTest {

    @Test
    fun aFreshReadyWheelStaysInRelaying() {
        assertNull(relayingExitReason(ConnectionState.Ready, frameReady = true, frameStale = false, frameAgeMillis = 120))
    }

    @Test
    fun aReadyLinkWhoseTelemetryWentStaleIsToldApartFromADrop() {
        assertEquals(
            "conn=Ready ready=false stale=true frameAgeMs=3400",
            relayingExitReason(ConnectionState.Ready, frameReady = false, frameStale = true, frameAgeMillis = 3_400),
        )
    }

    @Test
    fun aFailedLinkCarriesItsReasonAndMessage() {
        val failed = ConnectionState.Failed(ConnectionState.Failed.Reason.BLE_LINK_LOST, "status 8")
        assertEquals(
            "conn=Failed(BLE_LINK_LOST: status 8) ready=false stale=true frameAgeMs=0",
            relayingExitReason(failed, frameReady = false, frameStale = true, frameAgeMillis = 0),
        )
    }

    @Test
    fun aFailedLinkWithoutMessageAndADisconnectAreNamed() {
        assertEquals(
            "Failed(GATT_ERROR)",
            describeConnectionState(ConnectionState.Failed(ConnectionState.Failed.Reason.GATT_ERROR)),
        )
        assertEquals("Disconnected", describeConnectionState(ConnectionState.Disconnected))
    }
}
