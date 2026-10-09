/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.ble

import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.domain.wheel.gatt.GattServiceSpec
import com.rideflux.domain.wheel.gatt.GattSignature

/**
 * The GATT signatures RideFlux is allowed to match against, and the reason each
 * one is here.
 *
 * **Seeding rule (T02):** a row enters this table only when a note states it at
 * **L2+**, or when the **owner's own A2 capture** shows it. Everything else
 * stays out and is listed under [UNPROVEN] with a `TODO(T08): probe` marker,
 * because a wrong signature mis-routes a wheel while "unknown" does not
 * (`GATT_FINGERPRINT_IDENTIFICATION.md` §6.3).
 *
 * The three seeded rows are **deliberately non-discriminating**: they all
 * require the same single service/characteristic pair, because that is what the
 * evidence supports — no source in the workspace ties a *second* service or an
 * exclusion to G, K or V at L2+. The detector therefore reports
 * `AMBIGUOUS` for a `FFE0`/`FFE1` wheel and RideFlux falls back to its existing
 * name-hint inference, which is what it did before this table existed. The
 * value of the table today is that the ambiguity is *named* (the tie is logged
 * with the candidate ids) instead of being resolved silently to family G, and
 * that a proven discriminator can be added as one row.
 */
internal object WheelFamilySignatures {

    /**
     * `FFE0` + `FFE1`, the single-characteristic profile. Shared by G, K, N1 and
     * V — four families, one topology.
     */
    private val FFE0_FFE1 = listOf(
        GattServiceSpec(
            service = GattUuids.SERVICE_FFE0,
            requiredCharacteristics = setOf(GattUuids.CHAR_FFE1),
        ),
    )

    val SEEDED: List<GattSignature> = listOf(
        GattSignature(
            id = "G.ffe0-ffe1",
            family = WheelFamily.G,
            services = FFE0_FFE1,
            evidence = "Owner's A2 capture: notifications and RideFlux's writes share one value " +
                "handle 0x0025 = characteristic 0000ffe1-… (CAP-A-a2-live-capture.md:14; " +
                "CONFIRMED_CORRECT.md row 9, evidence W,R). The FFE0 service is the same " +
                "SINGLE_CHAR model; the community implementations (V-lib, WheelDash, WheelLog) " +
                "agree on the pair at L1 (GATT_V_FAMILY_BEGODE_COLLISION.md §7).",
        ),
        GattSignature(
            id = "K.ffe0-ffe1",
            family = WheelFamily.K,
            services = FFE0_FFE1,
            evidence = "L2 vendor-static: the KingSong vendor app sets both READ and WRITE to " +
                "0000ffe1-… (CROSS_BRAND_COMMAND_REFERENCE.md §1 and §1.1; FFE2 is a " +
                "WheelDash-only construct and is deliberately absent here).",
        ),
        GattSignature(
            id = "V.ffe0-ffe1",
            family = WheelFamily.V,
            services = FFE0_FFE1,
            evidence = "L2 vendor-static: NOSFET 1.1.3 and LeaperKim 1.4.8 both declare service " +
                "0000ffe0-… with characteristic 0000ffe1-… in utils/BtManager.java:65-70 " +
                "(GATT_V_FAMILY_BEGODE_COLLISION.md §7 table).",
        ),
    )

    /**
     * Rows that were considered and **rejected**, with the reason and what would
     * promote them. Nothing here may be matched until the promotion condition is
     * met — this list exists so the decision is reviewable rather than lost.
     *
     * ```
     * TODO(T08): probe — I1 split profile (FFE0[FFE4] + FFE5[FFE9])
     *   Evidence now: V-lib only (L1) — CROSS_BRAND_COMMAND_REFERENCE.md §1.
     *   Promote when: an Inmotion vendor artefact or a capture shows the pair,
     *   or the FFE0/FFE4/FFE5/FFE9 snapshot pool is attributed per model
     *   (CROSS_BRAND §1.1: the Dart snapshot names the UUIDs but no
     *   UUID→adapter edge is reachable).
     *
     * TODO(T08): probe — NUS families (I2, N2, VESC, SoFlow)
     *   Evidence now: L1, and four-way ambiguous on 6e400001 alone
     *   (GATT_FINGERPRINT_IDENTIFICATION.md §1, §7.4).
     *   Promote only via the ProbeStrategy path (§5bis): a VESC answers
     *   GetPkgInfo 0x65 0x00 and the others do not, so no passive signature
     *   can decide this set.
     *
     * TODO(T08): probe — N1 (Ninebot One) FFE0/FFE1
     *   Evidence now: V-lib only (L1) — CROSS_BRAND §1. Same shape as G/K/V,
     *   so it would only add a fourth tied candidate.
     *
     * REJECTED, do not import: the euc-ble-core fingerprint database
     *   (GATT_FINGERPRINT_IDENTIFICATION.md §4): KingSong's five services,
     *   Gotway's six (including FFF0/FFF1), and the Inmotion/Ninebot rows that
     *   hinge on 00002aa6. Every vendor-specific UUID in it returns zero hits in
     *   every vendor app in this workspace (§5.1) and the version field did not
     *   survive decompilation (§5.2). §6.3 says it outright: adopt the
     *   algorithm, import no data. §7 adds that the 2026-10-07 public reverses
     *   are L1 too, and that FFF0 has a second claimant (KuKirin), so it cannot
     *   be used to separate Begode from anything yet.
     * ```
     */
}
