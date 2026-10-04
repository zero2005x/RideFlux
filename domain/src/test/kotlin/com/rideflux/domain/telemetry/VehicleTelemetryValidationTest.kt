package com.rideflux.domain.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class VehicleTelemetryValidationTest {
    private fun bms(
        voltage: Float? = null, current: Float? = null, capacity: Float? = null,
        soh: Float? = null, cycles: Int? = null,
    ) = SmartBmsTelemetry(1L, voltage, current, capacity, soh, cycles)

    private fun scooter(
        speed: Float? = null, battery: Float? = null, total: Long? = null, trip: Long? = null,
        throttle: Float? = null, brake: Float? = null,
    ) = ScooterTelemetry(1L, speed, battery, total, trip, null, throttle, brake, null)

    @Test fun `value classes reject impossible values`() {
        assertThrows(IllegalArgumentException::class.java) { Millivolt(-1) }
        assertThrows(IllegalArgumentException::class.java) { Celsius(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Celsius(Float.POSITIVE_INFINITY) }
        assertEquals(0, Millivolt(0).value)
        assertEquals(-40f, Celsius(-40f).value, 0f)
    }

    @Test fun `smart bms accepts absent and boundary values`() {
        assertNull(bms().totalVoltageV)
        val edge = bms(0f, -5f, 0f, 100f, 0)
        assertEquals(100f, edge.stateOfHealthPercent)
        assertEquals(0f, bms(soh = 0f).stateOfHealthPercent)
    }

    @Test fun `smart bms rejects non finite negative and out of range values`() {
        assertThrows(IllegalArgumentException::class.java) { bms(voltage = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { bms(voltage = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { bms(current = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { bms(current = Float.NEGATIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { bms(capacity = -1f) }
        assertThrows(IllegalArgumentException::class.java) { bms(capacity = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { bms(soh = 100.1f) }
        assertThrows(IllegalArgumentException::class.java) { bms(soh = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { bms(soh = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { bms(cycles = -1) }
    }

    @Test fun `scooter telemetry accepts absent and boundary values`() {
        assertNull(scooter().speedKmh)
        val edge = scooter(0f, 100f, 0L, 0L, 100f, 0f)
        assertEquals(100f, edge.batteryPercent)
        assertEquals(0f, scooter(battery = 0f).batteryPercent)
    }

    @Test fun `scooter telemetry rejects impossible values`() {
        assertThrows(IllegalArgumentException::class.java) { scooter(speed = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { scooter(speed = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { scooter(battery = 100.1f) }
        assertThrows(IllegalArgumentException::class.java) { scooter(battery = -1f) }
        assertThrows(IllegalArgumentException::class.java) { scooter(battery = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { scooter(total = -1L) }
        assertThrows(IllegalArgumentException::class.java) { scooter(trip = -1L) }
        assertThrows(IllegalArgumentException::class.java) { scooter(throttle = 101f) }
        assertThrows(IllegalArgumentException::class.java) { scooter(throttle = -1f) }
        assertThrows(IllegalArgumentException::class.java) { scooter(throttle = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { scooter(brake = 101f) }
        assertThrows(IllegalArgumentException::class.java) { scooter(brake = -1f) }
        assertThrows(IllegalArgumentException::class.java) { scooter(brake = Float.NaN) }
    }
}
