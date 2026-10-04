package com.rideflux.domain.device

import com.rideflux.domain.telemetry.Millivolt
import com.rideflux.domain.telemetry.ScooterTelemetry
import com.rideflux.domain.telemetry.SmartBmsTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlevDeviceTest {
    @Test fun `three device classes remain independent`() {
        val devices: List<PlevDevice> = listOf(
            WheelDevice("wheel", "A2"),
            BmsDevice("bms", "JBD", SmartBmsTelemetry(1, cellVoltages = listOf(Millivolt(3_300)))),
            ScooterDevice("scooter", "M365", ScooterTelemetry(1, speedKmh = 0f)),
        )
        assertTrue(devices[0] is WheelDevice)
        assertTrue(devices[1] is BmsDevice)
        assertTrue(devices[2] is ScooterDevice)
        assertEquals(3_300, (devices[1] as BmsDevice).telemetry!!.cellVoltages.single().value)
    }

    @Test fun `telemetry rejects impossible physical values`() {
        assertThrows(IllegalArgumentException::class.java) { Millivolt(-1) }
        assertThrows(IllegalArgumentException::class.java) { ScooterTelemetry(1, speedKmh = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SmartBmsTelemetry(1, stateOfHealthPercent = 101f) }
    }
}
