/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.wheel.WheelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The resolver is the only place allowed to decide a family from discovery.
 * These cases pin the precedence and, more importantly, the cases that must
 * stay unresolved: a shared UUID set is not a family.
 */
class WheelSessionResolverTest {

    private val resolver = WheelSessionResolver(WheelCodecFactoryImpl())

    private fun snapshot(
        name: String? = null,
        vararg services: Pair<UUID, List<UUID>>,
    ) = GattSnapshot(deviceName = name, services = services.toMap())

    private fun single(name: String? = null) =
        snapshot(name, GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE1))

    private fun nus(name: String? = null) = snapshot(
        name,
        GattUuids.SERVICE_NUS to listOf(GattUuids.CHAR_NUS_RX, GattUuids.CHAR_NUS_TX),
    )

    private fun split(name: String? = null) = snapshot(
        name,
        GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE4),
        GattUuids.SERVICE_FFE5 to listOf(GattUuids.CHAR_FFE9),
    )

    @Test
    fun `unknown FFE0 FFE1 is ambiguous instead of silently G`() {
        val r = resolver.resolve(SessionHints(), single())
        assertNull(r.family)
        assertFalse(r.usable)
        assertEquals(ResolutionConfidence.AMBIGUOUS, r.confidence)
        assertEquals(
            listOf(WheelFamily.G, WheelFamily.GX, WheelFamily.K, WheelFamily.V, WheelFamily.N1),
            r.candidates,
        )
    }

    @Test
    fun `KingSong and Veteran names pick different families from the same table`() {
        val k = resolver.resolve(SessionHints(advertisedName = "KS-18XL"), single())
        val v = resolver.resolve(SessionHints(advertisedName = "Sherman S"), single())
        assertEquals(WheelFamily.K, k.family)
        assertEquals(ResolutionConfidence.GATT_PROBABLE, k.confidence)
        assertEquals(WheelFamily.V, v.family)
        assertEquals(ResolutionConfidence.GATT_PROBABLE, v.confidence)
        assertTrue(k.usable && v.usable)
        assertEquals(GattTopology.SINGLE_CHAR, k.topology)
        assertEquals(GattTopology.SINGLE_CHAR, v.topology)
    }

    @Test
    fun `manual selection wins and is reported as explicit`() {
        val r = resolver.resolve(SessionHints(manualFamily = WheelFamily.V), single("GotWay_75436"))
        assertEquals(WheelFamily.V, r.family)
        assertEquals(ResolutionConfidence.EXPLICIT, r.confidence)
        assertEquals(ResolutionSource.MANUAL, r.source)
    }

    @Test
    fun `manual selection that the table cannot carry is a conflict`() {
        val r = resolver.resolve(SessionHints(manualFamily = WheelFamily.G), nus("VESC"))
        assertNull(r.family)
        assertTrue(r.conflict)
        assertFalse(r.usable)
        assertEquals(ResolutionConfidence.CONFLICT, r.confidence)
        assertEquals(WheelFamily.G, r.requestedFamily)
        assertTrue(r.reason.contains("SINGLE_CHAR"))
    }

    @Test
    fun `saved profile is used when compatible`() {
        val r = resolver.resolve(
            SessionHints(savedFamily = WheelFamily.V, advertisedName = "GotWay_75436"),
            single("GotWay_75436"),
        )
        assertEquals(WheelFamily.V, r.family)
        assertEquals(ResolutionConfidence.SAVED, r.confidence)
    }

    @Test
    fun `incompatible saved profile is not forced and the table stays ambiguous`() {
        val r = resolver.resolve(SessionHints(savedFamily = WheelFamily.I2), single())
        assertNull(r.family)
        assertEquals(ResolutionConfidence.AMBIGUOUS, r.confidence)
        assertTrue(r.reason.contains("saved profile I2"))
    }

    @Test
    fun `saved profile incompatible but a name decides the table`() {
        val r = resolver.resolve(
            SessionHints(savedFamily = WheelFamily.I2, advertisedName = "KS-16X"),
            single(),
        )
        assertEquals(WheelFamily.K, r.family)
        assertEquals(ResolutionConfidence.GATT_PROBABLE, r.confidence)
    }

    @Test
    fun `NUS without a discriminator is ambiguous across I2 N2 and VESC`() {
        val r = resolver.resolve(SessionHints(), nus())
        assertNull(r.family)
        assertEquals(ResolutionConfidence.AMBIGUOUS, r.confidence)
        assertEquals(listOf(WheelFamily.I2, WheelFamily.N2, WheelFamily.VESC), r.candidates)
    }

    @Test
    fun `NUS with an Inmotion or Ninebot name keeps its path`() {
        assertEquals(WheelFamily.I2, resolver.resolve(SessionHints(advertisedName = "Inmotion V11"), nus()).family)
        assertEquals(WheelFamily.N2, resolver.resolve(SessionHints(advertisedName = "Ninebot Z10"), nus()).family)
    }

    @Test
    fun `manual VESC on NUS resolves explicitly`() {
        val r = resolver.resolve(SessionHints(manualFamily = WheelFamily.VESC), nus("VESC"))
        assertEquals(WheelFamily.VESC, r.family)
        assertEquals(GattTopology.NORDIC_UART, r.topology)
        assertEquals(ResolutionConfidence.EXPLICIT, r.confidence)
    }

    @Test
    fun `a model name contradicting the table is a conflict`() {
        val r = resolver.resolve(SessionHints(advertisedName = "KS-18XL"), nus())
        assertNull(r.family)
        assertTrue(r.conflict)
        assertEquals(WheelFamily.K, r.requestedFamily)
    }

    @Test
    fun `split FFE0 FFE5 resolves I1`() {
        val r = resolver.resolve(SessionHints(), split())
        assertEquals(WheelFamily.I1, r.family)
        assertEquals(ResolutionConfidence.GATT_EXACT, r.confidence)
        assertEquals(GattTopology.SPLIT_CHAR, r.topology)
    }

    @Test
    fun `two present structures without a discriminator are ambiguous over the union`() {
        val r = resolver.resolve(
            SessionHints(),
            snapshot(
                null,
                GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE1),
                GattUuids.SERVICE_NUS to listOf(GattUuids.CHAR_NUS_RX, GattUuids.CHAR_NUS_TX),
            ),
        )
        assertNull(r.family)
        assertEquals(ResolutionConfidence.AMBIGUOUS, r.confidence)
        assertEquals(
            // Group order is fixed (split, NUS, single), so the union keeps it.
            listOf(WheelFamily.I2, WheelFamily.N2, WheelFamily.VESC, WheelFamily.G, WheelFamily.GX, WheelFamily.K, WheelFamily.V, WheelFamily.N1),
            r.candidates,
        )
    }

    @Test
    fun `no supported topology is unsupported`() {
        val r = resolver.resolve(SessionHints(manualFamily = null), snapshot("mystery", UUID.randomUUID() to emptyList()))
        assertNull(r.family)
        assertEquals(ResolutionConfidence.UNSUPPORTED, r.confidence)
    }

    @Test
    fun `a Xiaomi FE95 table is not a wheel family`() {
        val r = resolver.resolve(
            SessionHints(),
            snapshot(null, GattUuids.SERVICE_FE95 to listOf(GattUuids.CHAR_MI_UPNP)),
        )
        assertNull(r.family)
        assertEquals(ResolutionConfidence.UNSUPPORTED, r.confidence)
        assertFalse(r.usable)
    }

    @Test
    fun `provisional uses manual then saved then name and never invents G`() {
        assertEquals(WheelFamily.V, resolver.provisional(SessionHints(manualFamily = WheelFamily.V)).family)
        assertEquals(WheelFamily.K, resolver.provisional(SessionHints(savedFamily = WheelFamily.K)).family)
        assertEquals(WheelFamily.G, resolver.provisional(SessionHints(advertisedName = "GotWay_75436")).family)
        assertNull(resolver.provisional(SessionHints(advertisedName = "mystery")).family)
    }

    @Test
    fun `advertisement hint never turns a shared UUID into a family`() {
        assertNull(
            resolver.advertisementHint(
                SessionHints(advertisedServices = setOf(GattUuids.SERVICE_NUS.toString())),
            ),
        )
    }
}
