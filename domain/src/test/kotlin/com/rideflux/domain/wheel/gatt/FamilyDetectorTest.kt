/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.wheel.gatt

import com.rideflux.domain.wheel.WheelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The detector's contract, per the T02 acceptance list: exact match, an extra
 * service, a missing characteristic, and a two-candidate tie.
 *
 * The signatures here are **synthetic** on purpose. The production table
 * (`WheelFamilySignatures`) may only contain rows backed by L2+ notes or the A2
 * capture, so the mechanics are pinned against invented UUIDs instead of
 * bending the evidence rule.
 */
class FamilyDetectorTest {

    private val svcA: UUID = UUID.fromString("0000aaa0-0000-1000-8000-00805f9b34fb")
    private val svcB: UUID = UUID.fromString("0000bbb0-0000-1000-8000-00805f9b34fb")
    private val charA1: UUID = UUID.fromString("0000aaa1-0000-1000-8000-00805f9b34fb")
    private val charA2: UUID = UUID.fromString("0000aaa2-0000-1000-8000-00805f9b34fb")
    private val charB1: UUID = UUID.fromString("0000bbb1-0000-1000-8000-00805f9b34fb")

    private fun signature(
        id: String,
        family: WheelFamily,
        generation: Int? = null,
        services: List<GattServiceSpec>,
        exactCount: Boolean = false,
    ) = GattSignature(
        id = id,
        family = family,
        generation = generation,
        services = services,
        requireExactServiceCount = exactCount,
        evidence = "synthetic fixture for FamilyDetectorTest",
    )

    @Test
    fun `a single matching signature is EXACT and carries its generation`() {
        val detector = FamilyDetector(
            listOf(
                signature(
                    id = "A.1",
                    family = WheelFamily.K,
                    generation = 2,
                    services = listOf(GattServiceSpec(svcA, setOf(charA1, charA2))),
                ),
            ),
        )

        val detection = detector.detect(mapOf(svcA to setOf(charA1, charA2)))

        assertEquals(WheelFamily.K, detection.family)
        assertEquals(2, detection.generation)
        assertEquals(DetectionConfidence.EXACT, detection.confidence)
        assertEquals(listOf("A.1"), detection.candidates)
    }

    @Test
    fun `two signature sets of one family with the same generation stay EXACT`() {
        val detector = FamilyDetector(
            listOf(
                signature("gen1", WheelFamily.G, generation = 1, services = listOf(GattServiceSpec(svcA, setOf(charA1)))),
                signature("gen2", WheelFamily.G, generation = 1, services = listOf(GattServiceSpec(svcB, setOf(charB1)))),
            ),
        )

        val detection = detector.detect(mapOf(svcA to setOf(charA1)))

        assertEquals(WheelFamily.G, detection.family)
        assertEquals(DetectionConfidence.EXACT, detection.confidence)
        assertEquals(1, detection.generation)
    }

    @Test
    fun `two signature sets of one family that disagree about the generation report no generation`() {
        val detector = FamilyDetector(
            listOf(
                signature("gen1", WheelFamily.G, generation = 1, services = listOf(GattServiceSpec(svcA, setOf(charA1)))),
                signature("gen2", WheelFamily.G, generation = 2, services = listOf(GattServiceSpec(svcA, setOf(charA1)))),
            ),
        )

        val detection = detector.detect(mapOf(svcA to setOf(charA1)))

        assertEquals(WheelFamily.G, detection.family)
        assertNull("the signatures disagree, so the generation is unknown", detection.generation)
    }

    @Test
    fun `an extra service invalidates a signature that requires an exact service count`() {
        val exact = signature(
            id = "exact",
            family = WheelFamily.V,
            services = listOf(GattServiceSpec(svcA, setOf(charA1))),
            exactCount = true,
        )
        val detector = FamilyDetector(listOf(exact))

        assertEquals(
            DetectionConfidence.EXACT,
            detector.detect(mapOf(svcA to setOf(charA1))).confidence,
        )

        val withExtra = detector.detect(mapOf(svcA to setOf(charA1), svcB to setOf(charB1)))
        assertNull("service count equal is part of this fingerprint", withExtra.family)
        assertEquals(DetectionConfidence.AMBIGUOUS, withExtra.confidence)
        assertTrue(withExtra.candidates.isEmpty())
    }

    @Test
    fun `an extra service is ignored by a signature that does not require an exact count`() {
        val detector = FamilyDetector(
            listOf(signature("loose", WheelFamily.V, services = listOf(GattServiceSpec(svcA, setOf(charA1))))),
        )

        val detection = detector.detect(mapOf(svcA to setOf(charA1), svcB to setOf(charB1)))

        assertEquals(WheelFamily.V, detection.family)
        assertEquals(DetectionConfidence.EXACT, detection.confidence)
    }

    @Test
    fun `a missing required characteristic means no match`() {
        val detector = FamilyDetector(
            listOf(
                signature(
                    id = "needs-both",
                    family = WheelFamily.I1,
                    services = listOf(GattServiceSpec(svcA, setOf(charA1, charA2))),
                ),
            ),
        )

        assertNull(detector.detect(mapOf(svcA to setOf(charA1))).family)
        assertEquals(
            WheelFamily.I1,
            detector.detect(mapOf(svcA to setOf(charA1, charA2))).family,
        )
    }

    @Test
    fun `a missing service means no match`() {
        val detector = FamilyDetector(
            listOf(signature("two-services", WheelFamily.I2, services = listOf(
                GattServiceSpec(svcA, setOf(charA1)),
                GattServiceSpec(svcB, setOf(charB1)),
            ))),
        )

        assertNull(detector.detect(mapOf(svcA to setOf(charA1))).family)
        assertEquals(
            WheelFamily.I2,
            detector.detect(mapOf(svcA to setOf(charA1), svcB to setOf(charB1))).family,
        )
    }

    @Test
    fun `an excluded characteristic is what separates two otherwise identical signatures`() {
        val detector = FamilyDetector(
            listOf(
                signature("plain", WheelFamily.N2, services = listOf(GattServiceSpec(svcA, setOf(charA1)))),
                signature(
                    "excludes-a2",
                    WheelFamily.I2,
                    services = listOf(
                        GattServiceSpec(svcA, setOf(charA1), excludedCharacteristics = setOf(charA2)),
                    ),
                ),
            ),
        )

        // charA2 present: the exclusion disqualifies "excludes-a2", so exactly one
        // family matches and the exclusion is doing the separating.
        assertEquals(
            WheelFamily.N2,
            detector.detect(mapOf(svcA to setOf(charA1, charA2))).family,
        )

        // charA2 absent: both rows satisfy their requirements, so this is a tie —
        // an exclusion only discriminates by being present. The detector reports
        // the tie instead of preferring the first row of the table.
        val tied = detector.detect(mapOf(svcA to setOf(charA1)))
        assertNull(tied.family)
        assertEquals(DetectionConfidence.AMBIGUOUS, tied.confidence)
        assertEquals(listOf("plain", "excludes-a2"), tied.candidates)
    }

    @Test
    fun `two candidates tie and nothing separates them`() {
        val detector = FamilyDetector(
            listOf(
                signature("K.1", WheelFamily.K, services = listOf(GattServiceSpec(svcA, setOf(charA1)))),
                signature("V.1", WheelFamily.V, services = listOf(GattServiceSpec(svcA, setOf(charA1)))),
            ),
        )

        val detection = detector.detect(mapOf(svcA to setOf(charA1)))

        assertNull("a tie must not auto-select", detection.family)
        assertEquals(DetectionConfidence.AMBIGUOUS, detection.confidence)
        assertEquals("both tied candidates are reported", listOf("K.1", "V.1"), detection.candidates)
    }

    @Test
    fun `a name hint breaks a tie only when it names a tied family`() {
        val detector = FamilyDetector(
            listOf(
                signature("K.1", WheelFamily.K, services = listOf(GattServiceSpec(svcA, setOf(charA1)))),
                signature("V.1", WheelFamily.V, services = listOf(GattServiceSpec(svcA, setOf(charA1)))),
            ),
        )

        val hinted = detector.detect(mapOf(svcA to setOf(charA1)), nameHint = WheelFamily.V)
        assertEquals(WheelFamily.V, hinted.family)
        assertEquals(DetectionConfidence.PROBABLE, hinted.confidence)
        assertEquals(listOf("K.1", "V.1"), hinted.candidates)

        val uselessHint = detector.detect(mapOf(svcA to setOf(charA1)), nameHint = WheelFamily.N1)
        assertNull("a hint that names no tied family cannot decide", uselessHint.family)
        assertEquals(DetectionConfidence.AMBIGUOUS, uselessHint.confidence)
    }

    @Test
    fun `nothing matched is AMBIGUOUS with no candidates`() {
        val detector = FamilyDetector(
            listOf(signature("K.1", WheelFamily.K, services = listOf(GattServiceSpec(svcA, setOf(charA1))))),
        )

        val detection = detector.detect(mapOf(svcB to setOf(charB1)))

        assertNull(detection.family)
        assertNull(detection.generation)
        assertEquals(DetectionConfidence.AMBIGUOUS, detection.confidence)
        assertTrue(detection.candidates.isEmpty())
    }

    @Test
    fun `an empty discovered table matches nothing`() {
        val detector = FamilyDetector(
            listOf(signature("K.1", WheelFamily.K, services = listOf(GattServiceSpec(svcA, setOf(charA1))))),
        )

        assertEquals(DetectionConfidence.AMBIGUOUS, detector.detect(emptyMap()).confidence)
    }

    // ---- the guards the findings ask for ------------------------------

    @Test
    fun `a signature with no required characteristic is refused rather than matching everything`() {
        val matchesEverything = signature(
            id = "empty",
            family = WheelFamily.G,
            services = listOf(GattServiceSpec(svcA)),
        )

        assertFalse("an empty requirement set is the hazard §3 warns about", matchesEverything.isUsable)
        assertFalse(matchesEverything.matches(mapOf(svcA to emptySet())))

        val detector = runCatching { FamilyDetector(listOf(matchesEverything)) }
        assertTrue(
            "the detector must refuse an unusable row instead of claiming every wheel",
            detector.isFailure,
        )
    }

    @Test
    fun `a detector with an empty table is refused`() {
        assertTrue(runCatching { FamilyDetector(emptyList()) }.isFailure)
    }

    @Test
    fun `duplicate signature ids are refused because a tie could not name them`() {
        val rows = listOf(
            signature("same", WheelFamily.G, services = listOf(GattServiceSpec(svcA, setOf(charA1)))),
            signature("same", WheelFamily.K, services = listOf(GattServiceSpec(svcB, setOf(charB1)))),
        )

        assertTrue(runCatching { FamilyDetector(rows) }.isFailure)
    }

    @Test
    fun `a characteristic cannot be both required and excluded on one service`() {
        val invalid = runCatching {
            GattServiceSpec(svcA, requiredCharacteristics = setOf(charA1), excludedCharacteristics = setOf(charA1))
        }

        assertTrue(invalid.isFailure)
    }

    @Test
    fun `the table is exposed in order so a caller can log it`() {
        val rows = listOf(
            signature("first", WheelFamily.G, services = listOf(GattServiceSpec(svcA, setOf(charA1)))),
            signature("second", WheelFamily.K, services = listOf(GattServiceSpec(svcB, setOf(charB1)))),
        )

        assertEquals(listOf("first", "second"), FamilyDetector(rows).table.map { it.id })
    }
}
