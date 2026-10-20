/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.wheel.gatt

import com.rideflux.domain.wheel.WheelFamily
import java.util.UUID

/** How much a [Detection] can be trusted. */
enum class DetectionConfidence {
    /** Exactly one family matched. The family (and, when declared, the generation) is decided. */
    EXACT,

    /**
     * More than one family matched and an independent hint picked one of them
     * — today the device name, via
     * [com.rideflux.domain.wheel.WheelNameClassifier]. Decide the family, but
     * say so: the GATT evidence alone did not.
     */
    PROBABLE,

    /**
     * Not decided: either several families matched with nothing to separate
     * them, or nothing matched at all.
     *
     * **An `AMBIGUOUS` detection must not be auto-selected.** Callers fall
     * back to their previous inference — for RideFlux that is the name hint
     * plus the single-UUID table in
     * [com.rideflux.data.ble.WheelCodecFactoryImpl] — and log
     * [Detection.candidates] so the tie is visible in a bug report. Returning
     * one of several tied families would be a guess, and a wrong guess
     * mis-routes the wheel.
     */
    AMBIGUOUS,
}

/**
 * Outcome of matching a discovered GATT table against a signature table.
 *
 * @param family the decided family, or `null` when [confidence] is
 *   [DetectionConfidence.AMBIGUOUS].
 * @param generation the protocol generation the matched signature declared,
 *   or `null` when the signature is silent about it or when several matched
 *   signatures declare different generations.
 * @param candidates ids of every signature that matched, in table order. Never
 *   empty for a [DetectionConfidence.PROBABLE] or a tied
 *   [DetectionConfidence.AMBIGUOUS], so a caller can log exactly what tied.
 */
data class Detection(
    val family: WheelFamily?,
    val generation: Int?,
    val confidence: DetectionConfidence,
    val candidates: List<String> = emptyList(),
)

/**
 * Matches a discovered GATT table against a list of [GattSignature]s.
 *
 * The algorithm is the one recovered in
 * `GATT_FINGERPRINT_IDENTIFICATION.md` §3, with the two guards that file asks
 * for:
 *
 *  1. **Composite** — every service of a signature must be present with its
 *     required characteristics, so one shared UUID (`FFE0`) no longer decides.
 *  2. **Negative** — a signature may require a characteristic to be *absent*,
 *     which is the only way to separate devices that share a positive
 *     requirement.
 *  3. **Empty signatures refuse to match** ([GattSignature.isUsable]) rather
 *     than claiming every wheel.
 *
 * Matching is **first-family-wins is deliberately not implemented**: every
 * signature is evaluated and a tie is reported as
 * [DetectionConfidence.AMBIGUOUS], because silently preferring the first row
 * of a table is exactly the failure mode the collision notes describe.
 *
 * Pure Kotlin, no Android types.
 */
class FamilyDetector(private val signatures: List<GattSignature>) {

    init {
        require(signatures.isNotEmpty()) { "an empty signature table can never identify a wheel" }
        val unusable = signatures.filterNot { it.isUsable }.map { it.id }
        require(unusable.isEmpty()) {
            "signatures without a required characteristic would match every wheel: $unusable"
        }
        val duplicateIds = signatures.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateIds.isEmpty()) { "duplicate signature ids: $duplicateIds" }
    }

    /** The signature table this detector matches against, in table order. */
    val table: List<GattSignature> get() = signatures

    /**
     * Match [discovered] — service UUIDs to their characteristic UUIDs.
     *
     * @param nameHint the family an independent signal resolved (the device
     *   name today), used only to break a tie and only when it names one of
     *   the tied families. Pass `null` when there is no hint.
     */
    fun detect(
        discovered: Map<UUID, Set<UUID>>,
        nameHint: WheelFamily? = null,
    ): Detection {
        val matched = signatures.filter { it.matches(discovered) }
        val families = matched.map { it.family }.distinct()

        if (families.isEmpty()) {
            return Detection(
                family = null,
                generation = null,
                confidence = DetectionConfidence.AMBIGUOUS,
            )
        }

        if (families.size == 1) {
            val family = families.single()
            return Detection(
                family = family,
                generation = generationFor(matched, family),
                confidence = DetectionConfidence.EXACT,
                candidates = matched.map { it.id },
            )
        }

        val hinted = nameHint?.takeIf { it in families }
        return Detection(
            family = hinted,
            generation = hinted?.let { generationFor(matched, it) },
            confidence = if (hinted != null) DetectionConfidence.PROBABLE else DetectionConfidence.AMBIGUOUS,
            candidates = matched.map { it.id },
        )
    }

    /**
     * The generation declared by the matched signatures of [family], or `null`
     * when they disagree or none declares one.
     */
    private fun generationFor(matched: List<GattSignature>, family: WheelFamily): Int? =
        matched.filter { it.family == family }
            .map { it.generation }
            .distinct()
            .singleOrNull()
}
