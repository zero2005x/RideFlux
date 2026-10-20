/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.wheel.gatt

import com.rideflux.domain.wheel.WheelFamily
import java.util.UUID

/**
 * One service requirement inside a [GattSignature].
 *
 * The three fields are the whole matching rule, and they are deliberately
 * separate because each one carries different evidence:
 *
 *  * [service] — the service must be present in the discovered table;
 *  * [requiredCharacteristics] — **every** one must be present on that service;
 *  * [excludedCharacteristics] — **every** one must be *absent* from it.
 *
 * The exclusion set is what separates devices that share a positive
 * requirement. `GATT_FINGERPRINT_IDENTIFICATION.md` §3 records the vendored
 * library using exactly this shape, and §4's Ninebot row shows the mechanism
 * doing real work (the same positive signature separated only by excluding a
 * characteristic).
 *
 * Pure data: no Android types, so a signature table can be unit-tested.
 */
data class GattServiceSpec(
    val service: UUID,
    val requiredCharacteristics: Set<UUID> = emptySet(),
    val excludedCharacteristics: Set<UUID> = emptySet(),
) {
    init {
        require(requiredCharacteristics.intersect(excludedCharacteristics).isEmpty()) {
            "a characteristic cannot be both required and excluded on the same service: " +
                requiredCharacteristics.intersect(excludedCharacteristics)
        }
    }
}

/**
 * A named GATT fingerprint for one protocol generation.
 *
 * A protocol may own **several** signatures, any one of which identifies it —
 * that is how one protocol covers more than one firmware generation
 * (`GATT_FINGERPRINT_IDENTIFICATION.md` §2). [generation] is the protocol
 * generation this signature declares, or `null` when the signature says
 * nothing about the generation.
 *
 * [evidence] is mandatory and must name the note and its level. T02 seeds this
 * table **only** from notes at L2+ or from the owner's A2 capture; everything
 * else stays out of the table and is marked `TODO(T08): probe` at the call
 * site, because a wrong signature actively mis-routes a wheel, which is worse
 * than reporting "unknown" (`GATT_FINGERPRINT_IDENTIFICATION.md` §6.3).
 */
data class GattSignature(
    val id: String,
    val family: WheelFamily,
    val generation: Int? = null,
    val services: List<GattServiceSpec>,
    /**
     * When `true`, the discovered table must contain **exactly** the services
     * of this signature. The vendored algorithm has no such rule — it ignores
     * extra services — so this defaults to `false` and is only set where a
     * note says the service *count* is part of the fingerprint
     * (`EUCWORLD_IMPLEMENTATION_2026-10-08.md` §1: "service count equal").
     */
    val requireExactServiceCount: Boolean = false,
    val evidence: String,
) {
    init {
        require(id.isNotBlank()) { "a signature needs a stable id so a tie can be logged" }
        require(evidence.isNotBlank()) { "signature $id has no evidence; unproven rows must not be seeded" }
    }

    /**
     * `false` for a signature that would match every wheel.
     *
     * An empty signature matches everything — Kotlin's `all {}` on an empty
     * list is `true` — and so does a signature whose every spec requires no
     * characteristic, because a service that is present trivially satisfies an
     * empty requirement set. Both are real hazards
     * (`GATT_FINGERPRINT_IDENTIFICATION.md` §3, §6.4), so an unusable
     * signature is refused at construction of the detector rather than
     * silently claiming every device.
     */
    val isUsable: Boolean =
        services.isNotEmpty() && services.any { it.requiredCharacteristics.isNotEmpty() }

    /** `true` when [discovered] satisfies every requirement of this signature. */
    fun matches(discovered: Map<UUID, Set<UUID>>): Boolean {
        if (!isUsable) return false
        if (requireExactServiceCount && discovered.size != services.size) return false
        return services.all { spec ->
            val characteristics = discovered[spec.service] ?: return@all false
            spec.requiredCharacteristics.all { it in characteristics } &&
                spec.excludedCharacteristics.none { it in characteristics }
        }
    }
}
