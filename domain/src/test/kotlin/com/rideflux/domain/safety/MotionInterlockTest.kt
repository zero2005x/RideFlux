package com.rideflux.domain.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MotionInterlockTest {
    @Test fun `three fresh zero frames are required`() {
        val gate = MotionInterlock()
        assertThrows(SecurityException::class.java) { gate.requireAllowed(DangerTier.CRITICAL, 0) }
        gate.observe(0f, 100)
        gate.observe(0f, 200)
        assertThrows(SecurityException::class.java) { gate.requireAllowed(DangerTier.CRITICAL, 200) }
        gate.observe(0f, 300)
        assertEquals(MotionState.STATIONARY_CONFIRMED, gate.state(300))
        gate.requireAllowed(DangerTier.CRITICAL, 300)
        gate.observe(1f, 400)
        assertEquals(MotionState.MOVING, gate.state(400))
        assertThrows(SecurityException::class.java) { gate.requireAllowed(DangerTier.CRITICAL, 400) }
    }

    @Test fun `unknown malformed and stale speed fail closed`() {
        val gate = MotionInterlock(maxSampleAgeMillis = 500)
        repeat(3) { gate.observe(0f, it * 100L) }
        assertEquals(MotionState.UNKNOWN, gate.state(701))
        gate.observe(Float.NaN, 702)
        assertEquals(MotionState.UNKNOWN, gate.state(702))
        repeat(3) { gate.observe(0f, 800 + it * 100L) }
        gate.observe(null, 1_100)
        assertThrows(SecurityException::class.java) { gate.requireAllowed(DangerTier.CRITICAL, 1_100) }
        assertThrows(SecurityException::class.java) { gate.requireAllowed(DangerTier.FORBIDDEN, 1_100) }
    }
}
