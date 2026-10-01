/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyg

import com.rideflux.domain.codec.DecodeEvent
import com.rideflux.protocol.testutil.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A Begode frame carries a voltage and nothing else about the battery, so the percentage depends
 * on how many cells are in series. The same 66.88 V is a full 16-cell pack and a nearly empty
 * 20-cell one; without the rider's answer the percentage must stay unknown.
 */
class BegodeBatteryPackTest {

    /**
     * A live-telemetry frame reporting 66.88 V (raw 6688 = 0x1A20): sample frame "A" from
     * WheelLog's GotwayAdapter.java with only the voltage field changed (see NOTICE).
     */
    private val frameAt6688 = hex(
        """
        55 AA 1A 20 00 00 00 00  00 00 01 2C FD CA 00 01
        FF F8 00 18 5A 5A 5A 5A
        """,
    )

    private fun batteryPercentOf(codec: BegodeWheelCodec): Float? {
        val events = codec.decode(codec.newState(), frameAt6688)
        return events.filterIsInstance<DecodeEvent.TelemetryUpdate>().single().snapshot.batteryPercent
    }

    // ------------------------------------------------------------ the curve

    @Test fun `a 16 cell pack behaves exactly like the existing curve`() {
        for (hundredths in 4800..7000 step 37) {
            assertEquals(
                "at $hundredths",
                BegodeBatteryCurve.refinedPercent(hundredths),
                BegodeBatteryCurve.percentFor(hundredths, 16),
            )
        }
    }

    @Test fun `the same voltage means very different charge on a bigger pack`() {
        assertEquals(100, BegodeBatteryCurve.percentFor(6688, 16))
        // 66.88 V over 20 cells is 3.34 V per cell: almost empty.
        assertEquals(6, BegodeBatteryCurve.percentFor(6688, 20))
        assertEquals(0, BegodeBatteryCurve.percentFor(6688, 24))
    }

    @Test fun `every supported pack reads full at its own full voltage`() {
        assertEquals(100, BegodeBatteryCurve.percentFor(6720, 16)) // 67.2 V
        assertEquals(100, BegodeBatteryCurve.percentFor(8400, 20)) // 84 V
        assertEquals(100, BegodeBatteryCurve.percentFor(10080, 24)) // 100.8 V
    }

    @Test fun `an unsupported pack size has no percentage`() {
        for (cells in intArrayOf(-1, 0, 1, 12, 17, 21, 32)) {
            assertNull("cells=$cells", BegodeBatteryCurve.percentFor(6688, cells))
        }
    }

    @Test fun `an absurd voltage cannot overflow the scaling`() {
        assertEquals(100, BegodeBatteryCurve.percentFor(Int.MAX_VALUE, 20))
    }

    // ------------------------------------------------------------ the codec

    @Test fun `without the rider's answer the battery percentage stays unknown`() {
        val codec = BegodeWheelCodec("AA:BB:CC:DD:EE:FF")
        val snapshot = codec.decode(codec.newState(), frameAt6688)
            .filterIsInstance<DecodeEvent.TelemetryUpdate>().single().snapshot

        assertNull(snapshot.batteryPercent)
        // Everything else the frame says is still reported.
        assertEquals(66.88f, snapshot.voltageV!!, 0.001f)
    }

    @Test fun `the answer selects the curve`() {
        assertEquals(100f, batteryPercentOf(BegodeWheelCodec("A", seriesCells = { 16 })))
        assertEquals(6f, batteryPercentOf(BegodeWheelCodec("A", seriesCells = { 20 })))
        assertEquals(0f, batteryPercentOf(BegodeWheelCodec("A", seriesCells = { 24 })))
    }

    @Test fun `a size the app does not offer is treated as unknown`() {
        assertNull(batteryPercentOf(BegodeWheelCodec("A", seriesCells = { 21 })))
    }

    @Test fun `a change of the answer applies to the next frame of a live connection`() {
        var cells: Int? = null
        val codec = BegodeWheelCodec("A", seriesCells = { cells })
        val state = codec.newState()
        fun next(): Float? = codec.decode(state, frameAt6688)
            .filterIsInstance<DecodeEvent.TelemetryUpdate>().single().snapshot.batteryPercent

        assertNull(next())
        cells = 20
        assertEquals(6f, next())
        cells = 16
        assertEquals(100f, next())
        assertNotNull(next())
    }
}
