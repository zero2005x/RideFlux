/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of RideFlux. It is licensed under the GNU General
 * Public License, version 3 or (at your option) any later version.
 * See the LICENSE file in the repository root for the full text.
 */
package com.rideflux.data.ble

import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.WheelNameClassifier
import java.util.Locale
import java.util.UUID

/**
 * Everything known about a device before (and while) its GATT table is read.
 *
 * The three sources have deliberately different authority:
 *
 *  * [manualFamily] — the rider explicitly chose a family for this connect.
 *    Authoritative: if the discovered table cannot carry it the session
 *    fails with a diagnostic instead of quietly using another family.
 *  * [savedFamily] — a family this process remembers choosing for the same
 *    MAC earlier. Trusted, but a contradiction is re-resolved from GATT.
 *  * [advertisedName] / [advertisedServices] — weak hints. They may narrow a
 *    tie, never override a contradictory discovered table.
 */
data class SessionHints(
    val manualFamily: WheelFamily? = null,
    val savedFamily: WheelFamily? = null,
    val advertisedName: String? = null,
    val advertisedServices: Set<String> = emptySet(),
)

/** One immutable read of the discovered GATT table. */
data class GattSnapshot(
    val deviceName: String?,
    val services: Map<UUID, List<UUID>>,
)

/** How the family was decided. */
enum class ResolutionConfidence {
    /** The rider explicitly selected this family and the table can carry it. */
    EXPLICIT,

    /** A remembered selection for the same MAC, consistent with the table. */
    SAVED,

    /** Exactly one family's topology is present. */
    GATT_EXACT,

    /** A weak name hint narrowed a topology tie. */
    GATT_PROBABLE,

    /** More than one family fits and nothing proves which. */
    AMBIGUOUS,

    /** No supported topology was discovered. */
    UNSUPPORTED,

    /** A manual/strong hint contradicts the discovered table. */
    CONFLICT,
}

/** Where the deciding evidence came from. */
enum class ResolutionSource { MANUAL, SAVED_PROFILE, GATT_TABLE, ADVERTISEMENT, NONE }

/**
 * The single decision that binds a session: family, topology, codec input and
 * the diagnostic trail. A session must use one resolution for the codec, the
 * notify/write channels and the bootstrap; mixing them is how a VESC poll ends
 * up on a Begode parser.
 */
data class WheelResolution(
    val family: WheelFamily?,
    val topology: GattTopology?,
    val confidence: ResolutionConfidence,
    val source: ResolutionSource,
    val reason: String,
    val candidates: List<WheelFamily> = emptyList(),
    val signatureIds: List<String> = emptyList(),
    val requestedFamily: WheelFamily? = null,
) {
    /** True only when a codec may be built and its bootstrap may be sent. */
    val usable: Boolean
        get() = family != null && topology != null &&
            confidence != ResolutionConfidence.AMBIGUOUS &&
            confidence != ResolutionConfidence.UNSUPPORTED &&
            confidence != ResolutionConfidence.CONFLICT

    val conflict: Boolean get() = confidence == ResolutionConfidence.CONFLICT
}

/**
 * Resolves a family from discovery evidence.
 *
 * ### Precedence (highest first)
 * 1. **Manual selection** — must be compatible with the discovered topology;
 *    incompatible is a hard [ResolutionConfidence.CONFLICT], never a silent
 *    switch to another family.
 * 2. **Saved profile** — used when compatible. When it contradicts the table
 *    the table is re-resolved; an ambiguous table stays ambiguous (the saved
 *    value is reported, not forced).
 * 3. **GATT structure** — split `FFE0[FFE4]+FFE5[FFE9]` → I1; Nordic UART
 *    (`…0002` + `…0003`) → I2 / N2 / VESC; single `FFE0[FFE1]` → G / K / V / N1.
 *    If several structures are present, all of them are candidates.
 * 4. **Name hint** — only narrows a tie inside a present structure. A model
 *    name that contradicts the table is a [ResolutionConfidence.CONFLICT].
 *
 * Shared UUIDs are never enough on their own: an unnamed `FFE0/FFE1` device is
 * [ResolutionConfidence.AMBIGUOUS] across G/K/V/N1, and an unnamed NUS device
 * is ambiguous across I2/N2/VESC. The old behaviour silently picked G / N2,
 * which is what put the wrong bootstrap on the wire.
 */
class WheelSessionResolver(
    private val codecFactory: BleWheelCodecFactory,
) {

    /**
     * Pre-connect guess used only to build a provisional codec/topology.
     * Never authoritative: the discovered table decides.
     */
    fun provisional(hints: SessionHints): WheelResolution {
        val family = hints.manualFamily ?: hints.savedFamily
            ?: WheelNameClassifier.classify(hints.advertisedName)
        return if (family == null) {
            WheelResolution(
                family = null,
                topology = null,
                confidence = ResolutionConfidence.UNSUPPORTED,
                source = ResolutionSource.NONE,
                reason = "no manual selection, saved profile or recognised name before discovery",
            )
        } else {
            WheelResolution(
                family = family,
                topology = codecFactory.topologyFor(family),
                confidence = if (hints.manualFamily != null) {
                    ResolutionConfidence.EXPLICIT
                } else {
                    ResolutionConfidence.GATT_PROBABLE
                },
                source = if (hints.manualFamily != null) {
                    ResolutionSource.MANUAL
                } else {
                    ResolutionSource.ADVERTISEMENT
                },
                reason = "pre-connect ${if (hints.manualFamily != null) "manual" else "advertised"} hint",
            )
        }
    }

    /** Full resolution from the discovered table. */
    fun resolve(hints: SessionHints, snapshot: GattSnapshot): WheelResolution {
        val table = canonicalTable(snapshot.services)
        val groups = presentGroups(table)
        val candidates = groups.flatMap { it.families }.distinct()
        val nameFamily = WheelNameClassifier.classify(
            hints.advertisedName ?: snapshot.deviceName,
        )
        // Signature ids are diagnostic only. The detector lives on the concrete
        // factory, so a test fake that implements the interface simply reports
        // no ids instead of forcing the interface to grow a method.
        val signatureIds = (codecFactory as? WheelCodecFactoryImpl)
            ?.detectGattTable(snapshot.services, hints.advertisedName ?: snapshot.deviceName)
            ?.candidates
            .orEmpty()

        fun result(
            family: WheelFamily?,
            confidence: ResolutionConfidence,
            source: ResolutionSource,
            reason: String,
            topology: GattTopology? = family?.let(codecFactory::topologyFor),
            requested: WheelFamily? = null,
        ) = WheelResolution(
            family = family,
            topology = topology,
            confidence = confidence,
            source = source,
            reason = reason,
            candidates = candidates,
            signatureIds = signatureIds,
            requestedFamily = requested,
        )

        // 1. Manual selection: authoritative when the table can carry it.
        hints.manualFamily?.let { manual ->
            return if (groups.any { manual in it.families }) {
                result(
                    manual, ResolutionConfidence.EXPLICIT, ResolutionSource.MANUAL,
                    "manual selection $manual matches the discovered ${groupOf(manual, groups)} topology",
                )
            } else {
                result(
                    null, ResolutionConfidence.CONFLICT, ResolutionSource.MANUAL,
                    "manual selection $manual needs ${codecFactory.topologyFor(manual)} but the device " +
                        "exposes ${groups.joinToString { it.label }.ifEmpty { "no supported topology" }}",
                    requested = manual,
                )
            }
        }

        // 2. Saved profile: trusted when compatible, re-resolved when not.
        hints.savedFamily?.let { saved ->
            if (groups.any { saved in it.families }) {
                return result(
                    saved, ResolutionConfidence.SAVED, ResolutionSource.SAVED_PROFILE,
                    "saved profile $saved matches the discovered ${groupOf(saved, groups)} topology",
                )
            }
        }

        if (groups.isEmpty()) {
            return result(
                null, ResolutionConfidence.UNSUPPORTED, ResolutionSource.GATT_TABLE,
                "no FFE0/FFE1, split FFE0/FFE5 or Nordic UART topology was discovered",
            )
        }

        // 3. Name hint that contradicts every present structure is a conflict.
        if (nameFamily != null && nameFamily !in candidates) {
            return result(
                null, ResolutionConfidence.CONFLICT, ResolutionSource.ADVERTISEMENT,
                "advertised name maps to $nameFamily, but the device exposes " +
                    groups.joinToString { it.label },
                requested = nameFamily,
            )
        }

        // 4. Narrow inside the present structure(s).
        val narrowed = when {
            nameFamily != null && nameFamily in candidates -> listOf(nameFamily)
            groups.size == 1 -> groups.single().families
            else -> candidates
        }.distinct()

        if (narrowed.size == 1) {
            val family = narrowed.single()
            val byName = nameFamily == family
            return result(
                family,
                if (byName) ResolutionConfidence.GATT_PROBABLE else ResolutionConfidence.GATT_EXACT,
                if (byName) ResolutionSource.ADVERTISEMENT else ResolutionSource.GATT_TABLE,
                if (byName) {
                    "name hint $family is consistent with the discovered ${groupOf(family, groups)} topology"
                } else {
                    "only ${groupOf(family, groups)} is present"
                },
            )
        }

        return result(
            null, ResolutionConfidence.AMBIGUOUS, ResolutionSource.GATT_TABLE,
            "shared ${groups.joinToString { it.label }} topology fits ${narrowed.joinToString()}" +
                " and no name/manual/saved discriminator exists" +
                if (hints.savedFamily != null && hints.savedFamily !in candidates) {
                    "; saved profile ${hints.savedFamily} is incompatible and was not forced"
                } else {
                    ""
                },
        )
    }

    /** Family for the scan list, or null when only a shared topology is known. */
    fun advertisementHint(hints: SessionHints): WheelFamily? =
        hints.manualFamily ?: hints.savedFamily ?: WheelNameClassifier.classify(hints.advertisedName)

    // ---- topology grouping ---------------------------------------------

    private enum class Group(val label: String, val families: List<WheelFamily>) {
        SPLIT("split FFE0[FFE4]/FFE5[FFE9]", listOf(WheelFamily.I1)),
        NUS("Nordic UART", listOf(WheelFamily.I2, WheelFamily.N2, WheelFamily.VESC)),
        SINGLE("single FFE0[FFE1]", listOf(WheelFamily.G, WheelFamily.GX, WheelFamily.K, WheelFamily.V, WheelFamily.N1)),
    }

    private fun presentGroups(table: Map<String, Set<String>>): List<Group> = buildList {
        if (FFE4 in table[FFE0].orEmpty() && FFE9 in table[FFE5].orEmpty()) add(Group.SPLIT)
        if (NUS_RX in table[NUS].orEmpty() && NUS_TX in table[NUS].orEmpty()) add(Group.NUS)
        if (FFE1 in table[FFE0].orEmpty()) add(Group.SINGLE)
    }

    private fun groupOf(family: WheelFamily, groups: List<Group>): String =
        groups.firstOrNull { family in it.families }?.label ?: "unknown"

    /** Canonical lower-case 128-bit table, so short UUID spellings compare equal. */
    private fun canonicalTable(services: Map<UUID, List<UUID>>): Map<String, Set<String>> =
        services.mapKeys { canonicalUuid(it.key.toString()) }
            .mapValues { (_, chars) -> chars.mapTo(mutableSetOf()) { canonicalUuid(it.toString()) } }

    private companion object {
        private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
        val FFE0 = canonicalUuid(GattUuids.SERVICE_FFE0.toString())
        val FFE1 = canonicalUuid(GattUuids.CHAR_FFE1.toString())
        val FFE4 = canonicalUuid(GattUuids.CHAR_FFE4.toString())
        val FFE5 = canonicalUuid(GattUuids.SERVICE_FFE5.toString())
        val FFE9 = canonicalUuid(GattUuids.CHAR_FFE9.toString())
        val NUS = canonicalUuid(GattUuids.SERVICE_NUS.toString())
        val NUS_RX = canonicalUuid(GattUuids.CHAR_NUS_RX.toString())
        val NUS_TX = canonicalUuid(GattUuids.CHAR_NUS_TX.toString())

        fun canonicalUuid(raw: String): String {
            val s = raw.trim().removePrefix("0x").removePrefix("0X").lowercase(Locale.ROOT)
            return when (s.length) {
                4 -> "0000$s$BASE_SUFFIX"
                8 -> "$s$BASE_SUFFIX"
                else -> s
            }
        }
    }
}
