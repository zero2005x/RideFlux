package com.rideflux.protocol.familyg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BegodePhase2Test {
    @Test fun `WY plan uses four independent decimal writes with transport delays`() {
        val plan = BegodeCommandBuilder.pedalSpeedWritePlan(5)
        assertEquals(listOf("W", "Y", "0", "5"), plan.writes.map { String(it, Charsets.US_ASCII) })
        assertEquals(listOf(500L, 550L, 600L), plan.delaysAfterWriteMs)
        assertEquals("03", BegodeCommandBuilder.pedalSpeedWritePlan(0).writes.drop(2).joinToString("") { String(it) })
        assertEquals("90", BegodeCommandBuilder.pedalSpeedWritePlan(120).writes.drop(2).joinToString("") { String(it) })
    }

    @Test fun `vendor mode and alarm each mask their own three bits`() {
        val frame = BegodeFrame.SettingsAndOdometer(0, 0xFFFF, 0, 0, 0, 0, 0, 0)
        assertEquals(7, frame.rideMode)
        assertEquals(7, frame.speedAlarmMode)
    }

    @Test fun `longest model token wins without inferring battery configuration`() {
        assertEquals("EX-N", BegodeModelRegistry.identify("Begode EX-N")?.name)
        assertEquals("Master Pro", BegodeModelRegistry.identify("Begode Master Pro")?.name)
        assertNull(BegodeModelRegistry.identify("unknown")?.seriesCells)
        assertEquals(22, BegodeModelRegistry.models.size)
    }
}
