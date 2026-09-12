/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.wheel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The [WheelFamily] KDoc states that a unit test pins the enum against
 * the spec's family-key list, because the *name* is the persistence and
 * nav-deep-link key: renaming or reordering an entry silently breaks
 * previously stored family strings, which then fall back to [WheelFamily.G].
 *
 * This is that test.
 */
class WheelFamilyTest {

    /**
     * Names and order, exactly as the family-key table in
     * `clean-room/spec/PROTOCOL_SPEC.md` lists them. Changing this list
     * is a persistence-breaking change, not a refactor.
     */
    private val specFamilyKeys = listOf("G", "GX", "K", "V", "N1", "N2", "I1", "I2")

    @Test
    fun `enum names and order match the spec family-key table`() {
        assertEquals(specFamilyKeys, WheelFamily.entries.map { it.name })
    }

    @Test
    fun `every spec key round-trips through valueOf`() {
        for (key in specFamilyKeys) {
            assertEquals(key, WheelFamily.valueOf(key).name)
        }
    }

    @Test
    fun `an unknown persisted key does not resolve to a family`() {
        // Callers must handle this explicitly rather than defaulting: a
        // silent fallback to G would route a Veteran through the Begode
        // codec. entryOrNull-style lookup is the safe shape.
        assertNull(WheelFamily.entries.firstOrNull { it.name == "Z9" })
        assertNull(WheelFamily.entries.firstOrNull { it.name == "g" })
    }

    @Test
    fun `family keys are unique`() {
        val names = WheelFamily.entries.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }
}
