/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.gatt.Detection
import com.rideflux.domain.wheel.gatt.DetectionConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T02 acceptance: the post-connect resolver must return exactly what it returned
 * before the GATT signature detector existed.
 *
 * The table fed here is the owner's A2 as captured: notifications and writes on
 * one value handle `0x0025` = characteristic `0000ffe1-…`
 * (`CAP-A-a2-live-capture.md:14`), i.e. service `FFE0` with a single
 * notify+write characteristic `FFE1` (`CONFIRMED_CORRECT.md` row 9).
 *
 * The interesting assertion is the pair: the detector **cannot** decide this
 * wheel (G, K and V all advertise the same pair), so it reports AMBIGUOUS with
 * the tied candidates — and the resolver still answers `G`, unchanged, through
 * the fallback. Both halves are pinned so a future "fix" that makes the detector
 * auto-select cannot pass silently.
 */
class A2GattRegressionTest {

    private val factory = WheelCodecFactoryImpl()

    private val a2Table = mapOf(
        GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE1),
    )

    @Test
    fun `the A2 still resolves to family G with the detector wired in`() {
        assertEquals(WheelFamily.G, factory.inferFromGattTable(a2Table, name = "Begode"))
        assertEquals(WheelFamily.G, factory.inferFromGattTable(a2Table, name = null))
        assertEquals(WheelFamily.G, factory.inferFromGattTable(a2Table, name = "unrecognised board"))
    }

    @Test
    fun `the A2 detection is reported as ambiguous with the tied candidates`() {
        val detection = factory.detectGattTable(a2Table, name = null)

        assertNull("G, K and V share FFE0/FFE1, so no family may be auto-selected", detection.family)
        assertEquals(DetectionConfidence.AMBIGUOUS, detection.confidence)
        assertEquals(
            "every seeded signature that matched is named",
            listOf("G.ffe0-ffe1", "K.ffe0-ffe1", "V.ffe0-ffe1"),
            detection.candidates,
        )
    }

    @Test
    fun `a name hint turns the A2 tie into a decision without changing the outcome`() {
        val detection = factory.detectGattTable(a2Table, name = "Begode A2")

        assertEquals(WheelFamily.G, detection.family)
        assertEquals(DetectionConfidence.PROBABLE, detection.confidence)
        assertEquals(
            "a PROBABLE result still names what tied",
            listOf("G.ffe0-ffe1", "K.ffe0-ffe1", "V.ffe0-ffe1"),
            detection.candidates,
        )
    }

    @Test
    fun `the fallback is reported to the caller for logging`() {
        val reported = mutableListOf<Detection>()
        val logging = WheelCodecFactoryImpl(onAmbiguousDetection = { reported += it })

        assertEquals(WheelFamily.G, logging.inferFromGattTable(a2Table, name = null))
        assertEquals(1, reported.size)
        assertEquals(DetectionConfidence.AMBIGUOUS, reported.single().confidence)

        // A decided wheel must not be reported as a fallback.
        assertEquals(WheelFamily.K, logging.inferFromGattTable(a2Table, name = "KS-16X"))
        assertEquals(1, reported.size)
    }

    @Test
    fun `nothing about the other pre-existing GATT outcomes changed`() {
        val split = mapOf(
            GattUuids.SERVICE_FFE0 to listOf(GattUuids.CHAR_FFE4),
            GattUuids.SERVICE_FFE5 to listOf(GattUuids.CHAR_FFE9),
        )
        assertEquals(WheelFamily.I1, factory.inferFromGattTable(split, name = "unknown"))

        val nus = mapOf(
            GattUuids.SERVICE_NUS to listOf(GattUuids.CHAR_NUS_RX, GattUuids.CHAR_NUS_TX),
        )
        assertEquals(WheelFamily.I2, factory.inferFromGattTable(nus, name = "V14"))
        assertEquals(WheelFamily.N2, factory.inferFromGattTable(nus, name = "Unknown"))

        assertEquals(WheelFamily.K, factory.inferFromGattTable(a2Table, name = "ROCKWHEEL"))
        assertEquals(WheelFamily.V, factory.inferFromGattTable(a2Table, name = "SHERMAN"))
        assertEquals(WheelFamily.N1, factory.inferFromGattTable(a2Table, name = "NINEBOT Z10"))

        val unrelated = mapOf(GattUuids.SERVICE_FE95 to listOf(GattUuids.CHAR_MI_UPNP))
        assertNull(factory.inferFromGattTable(unrelated, name = null))
    }

    @Test
    fun `an unusable signature table is refused at construction`() {
        val refused = runCatching {
            WheelCodecFactoryImpl(
                familyDetector = com.rideflux.domain.wheel.gatt.FamilyDetector(emptyList()),
            )
        }

        assertTrue("an empty table would silently match nothing everywhere", refused.isFailure)
    }
}
