/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.protocol.familyg.BegodeWheelCodec
import com.rideflux.protocol.familyi1.InmotionI1WheelCodec
import com.rideflux.protocol.familyi2.InmotionI2WheelCodec
import com.rideflux.protocol.familyk.KingSongWheelCodec
import com.rideflux.protocol.familyn.NinebotN1WheelCodec
import com.rideflux.protocol.familyn.NinebotN2WheelCodec
import com.rideflux.protocol.familyv.VeteranWheelCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelCodecFactoryImplTest {

    private val factory = WheelCodecFactoryImpl()

    @Test
    fun `forFamilyWithAddress returns the correct concrete codec for every family`() {
        val mac = "AA:BB:CC:DD:EE:FF"
        assertTrue(factory.forFamilyWithAddress(WheelFamily.G, mac) is BegodeWheelCodec)
        assertTrue(factory.forFamilyWithAddress(WheelFamily.GX, mac) is BegodeWheelCodec)
        assertTrue(factory.forFamilyWithAddress(WheelFamily.K, mac) is KingSongWheelCodec)
        assertTrue(factory.forFamilyWithAddress(WheelFamily.V, mac) is VeteranWheelCodec)
        assertTrue(factory.forFamilyWithAddress(WheelFamily.N1, mac) is NinebotN1WheelCodec)
        assertTrue(factory.forFamilyWithAddress(WheelFamily.N2, mac) is NinebotN2WheelCodec)
        assertTrue(factory.forFamilyWithAddress(WheelFamily.I1, mac) is InmotionI1WheelCodec)
        assertTrue(factory.forFamilyWithAddress(WheelFamily.I2, mac) is InmotionI2WheelCodec)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `forFamily rejects the address-less overload instead of emitting a blank MAC`() {
        factory.forFamily(WheelFamily.G)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `forFamilyWithAddress rejects a blank address`() {
        factory.forFamilyWithAddress(WheelFamily.G, "  ")
    }

    @Test
    fun `inferFromGattServiceUuids accepts short 16-bit UUID forms`() {
        assertEquals(WheelFamily.I1, factory.inferFromGattServiceUuids(setOf("FFE0", "ffe5")))
        assertEquals(WheelFamily.G, factory.inferFromGattServiceUuids(setOf("ffe0")))
    }

    @Test
    fun `inferFromGattServiceUuids maps FFE0+FFE5 to I1`() {
        val uuids = setOf(
            "0000ffe0-0000-1000-8000-00805f9b34fb",
            "0000ffe5-0000-1000-8000-00805f9b34fb",
        )
        assertEquals(WheelFamily.I1, factory.inferFromGattServiceUuids(uuids))
    }

    @Test
    fun `inferFromGattServiceUuids maps NUS to I2`() {
        val uuids = setOf("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        assertEquals(WheelFamily.I2, factory.inferFromGattServiceUuids(uuids))
    }

    @Test
    fun `inferFromGattServiceUuids maps plain FFE0 to G`() {
        val uuids = setOf("0000ffe0-0000-1000-8000-00805f9b34fb")
        assertEquals(WheelFamily.G, factory.inferFromGattServiceUuids(uuids))
    }

    @Test
    fun `inferFromGattServiceUuids is case-insensitive`() {
        val uuids = setOf("0000FFE0-0000-1000-8000-00805F9B34FB")
        assertEquals(WheelFamily.G, factory.inferFromGattServiceUuids(uuids))
    }

    @Test
    fun `inferFromGattServiceUuids returns null for unrelated UUIDs`() {
        val uuids = setOf("0000180f-0000-1000-8000-00805f9b34fb")  // Battery service
        assertNull(factory.inferFromGattServiceUuids(uuids))
    }

    @Test
    fun `inferFromGattServiceUuids returns null for empty set`() {
        assertNull(factory.inferFromGattServiceUuids(emptySet()))
    }

    // ---- inferFromAdvertisement (name first, UUIDs as fallback) --------

    @Test
    fun `inferFromAdvertisement resolves an Inmotion legacy wheel that advertises no service UUID`() {
        // The V5F puts only its local name on the air, so the UUID-only
        // resolver cannot see it — which is exactly why the wheel was
        // absent from the scan list.
        assertNull(factory.inferFromGattServiceUuids(emptySet()))
        assertEquals(
            WheelFamily.I1,
            factory.inferFromAdvertisement("V5F-2A4AC0", emptySet()),
        )
    }

    @Test
    fun `inferFromAdvertisement prefers the name over an ambiguous UUID set`() {
        // FFE0 alone resolves to G, but a KS-16X on the same profile is
        // a KingSong: the name is the more specific signal.
        val ffe0 = setOf("0000ffe0-0000-1000-8000-00805f9b34fb")
        assertEquals(WheelFamily.G, factory.inferFromGattServiceUuids(ffe0))
        assertEquals(WheelFamily.K, factory.inferFromAdvertisement("KS-16X", ffe0))
    }

    @Test
    fun `inferFromAdvertisement falls back to UUIDs for an unrecognised name`() {
        val uuids = setOf(
            "0000ffe0-0000-1000-8000-00805f9b34fb",
            "0000ffe5-0000-1000-8000-00805f9b34fb",
        )
        assertEquals(WheelFamily.I1, factory.inferFromAdvertisement("Unlabelled board", uuids))
        assertEquals(WheelFamily.I1, factory.inferFromAdvertisement(null, uuids))
    }

    @Test
    fun `inferFromAdvertisement returns null when neither signal resolves`() {
        assertNull(factory.inferFromAdvertisement("AirPods Pro", emptySet()))
        assertNull(factory.inferFromAdvertisement(null, emptySet()))
    }

    @Test
    fun `topologyFor maps families to the correct GATT topology`() {
        assertEquals(GattTopology.SINGLE_CHAR, factory.topologyFor(WheelFamily.G))
        assertEquals(GattTopology.SINGLE_CHAR, factory.topologyFor(WheelFamily.GX))
        assertEquals(GattTopology.SINGLE_CHAR, factory.topologyFor(WheelFamily.K))
        assertEquals(GattTopology.SINGLE_CHAR, factory.topologyFor(WheelFamily.N1))
        assertEquals(GattTopology.SINGLE_CHAR, factory.topologyFor(WheelFamily.V))
        assertEquals(GattTopology.SPLIT_CHAR, factory.topologyFor(WheelFamily.I1))
        assertEquals(GattTopology.NORDIC_UART, factory.topologyFor(WheelFamily.N2))
        assertEquals(GattTopology.NORDIC_UART, factory.topologyFor(WheelFamily.I2))
    }
}
