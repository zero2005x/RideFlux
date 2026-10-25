/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.catalog

import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.protocol.familyg.BegodeModelRegistry
import com.rideflux.protocol.familyi2.InmotionModelRegistry
import com.rideflux.protocol.familyk.KingSongModelRegistry
import com.rideflux.protocol.familyv.VeteranModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Programmatic catalog counts.
 *
 * These are **vocabulary sizes**, not the number of supported vehicles: a row
 * in a registry is not proof that its telemetry layout, controls or firmware
 * range are implemented. The successor support matrix carries that distinction
 * per model.
 */
class ModelCatalogCountsTest {

    @Test
    fun `catalog vocabulary counts are pinned`() {
        assertEquals(22, BegodeModelRegistry.models.size)
        assertEquals(21, KingSongModelRegistry.models.size)
        assertEquals(31, InmotionModelRegistry.all.size)
        assertEquals(12, VeteranModelRegistry.models.size)
        assertEquals(10, VeteranModelRegistry.vehicles.size)
        assertEquals(2, VeteranModelRegistry.models.count { it.isFirmwareSentinel })
    }

    @Test
    fun `family enum has nine routing keys while codec construction shares G and GX`() {
        assertEquals(9, WheelFamily.entries.size)
        assertEquals(
            listOf("G", "GX", "K", "V", "N1", "N2", "I1", "I2", "VESC"),
            WheelFamily.entries.map { it.name },
        )
    }

    @Test
    fun `Inmotion exact model matching never lets v11 swallow v11y`() {
        assertEquals("v11", InmotionModelRegistry.find("V11")?.key)
        assertEquals("v11y", InmotionModelRegistry.find("V11Y")?.key)
        assertEquals("v11y", InmotionModelRegistry.find("v11y-1")?.key)
    }
}
