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
    fun `forFamily returns the correct concrete codec for every family`() {
        assertTrue(factory.forFamily(WheelFamily.G) is BegodeWheelCodec)
        assertTrue(factory.forFamily(WheelFamily.GX) is BegodeWheelCodec)
        assertTrue(factory.forFamily(WheelFamily.K) is KingSongWheelCodec)
        assertTrue(factory.forFamily(WheelFamily.V) is VeteranWheelCodec)
        assertTrue(factory.forFamily(WheelFamily.N1) is NinebotN1WheelCodec)
        assertTrue(factory.forFamily(WheelFamily.N2) is NinebotN2WheelCodec)
        assertTrue(factory.forFamily(WheelFamily.I1) is InmotionI1WheelCodec)
        assertTrue(factory.forFamily(WheelFamily.I2) is InmotionI2WheelCodec)
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
