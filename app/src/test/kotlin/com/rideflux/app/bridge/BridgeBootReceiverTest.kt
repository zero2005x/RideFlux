/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BridgeBootReceiverTest {

    @Test
    fun isBackgroundStartRestriction_detectsIllegalStateException() {
        assertTrue(BridgeBootReceiver.isBackgroundStartRestriction(IllegalStateException("Not allowed to start service")))
    }

    @Test
    fun isBackgroundStartRestriction_detectsSecurityException() {
        assertTrue(BridgeBootReceiver.isBackgroundStartRestriction(SecurityException("Permission denied")))
    }

    @Test
    fun isBackgroundStartRestriction_detectsForegroundServiceStartNotAllowedExceptionByName() {
        class MockForegroundServiceStartNotAllowedException : IllegalStateException("Service start rejected")
        assertTrue(BridgeBootReceiver.isBackgroundStartRestriction(MockForegroundServiceStartNotAllowedException()))
    }

    @Test
    fun isBackgroundStartRestriction_returnsFalseForUnrelatedExceptions() {
        assertFalse(BridgeBootReceiver.isBackgroundStartRestriction(IOException("Disk full")))
        assertFalse(BridgeBootReceiver.isBackgroundStartRestriction(IllegalArgumentException("Bad argument")))
        assertFalse(BridgeBootReceiver.isBackgroundStartRestriction(NullPointerException("Missing value")))
    }
}
