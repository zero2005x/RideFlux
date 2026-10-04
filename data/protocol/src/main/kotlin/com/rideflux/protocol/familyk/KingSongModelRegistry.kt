/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyk

/**
 * Vendor-static model-name vocabulary. Names alone do not establish battery curves, safe
 * speed limits or PWM thresholds, so those values deliberately remain unknown.
 */
object KingSongModelRegistry {
    enum class Provenance { EUC_CANDIDATE, UNCLASSIFIED }

    data class Model(
        val name: String,
        val provenance: Provenance,
        val wheelDiameterInches: Int? = null,
        val packSeriesCells: Int? = null,
        val tiltbackLimitKmh: Int? = null,
        val pwmAlarmPercent: Int? = null,
    )

    // EUC World uh/c0.java model literals, plus KingSong names requested in Phase 2.
    // The short KS-S and KS-F literals could match scooters in the combined app.
    val models: List<Model> = listOf(
        Model("KS-14D", Provenance.EUC_CANDIDATE, 14),
        Model("KS-14M", Provenance.EUC_CANDIDATE, 14),
        Model("KS-14S", Provenance.EUC_CANDIDATE, 14),
        Model("KS-16S", Provenance.EUC_CANDIDATE, 16),
        Model("KS-16X", Provenance.EUC_CANDIDATE, 16),
        Model("KS-16XS", Provenance.EUC_CANDIDATE, 16),
        Model("KS-18L", Provenance.EUC_CANDIDATE, 18),
        Model("KS-18XL", Provenance.EUC_CANDIDATE, 18),
        Model("KS-S16", Provenance.UNCLASSIFIED),
        Model("KS-S18", Provenance.EUC_CANDIDATE),
        Model("KS-S19", Provenance.UNCLASSIFIED),
        Model("KS-S20", Provenance.EUC_CANDIDATE),
        Model("KS-S22", Provenance.EUC_CANDIDATE),
        Model("KS-S22 PRO", Provenance.EUC_CANDIDATE),
        Model("KS-S2", Provenance.UNCLASSIFIED),
        Model("KS-F", Provenance.UNCLASSIFIED),
        Model("KS-F1", Provenance.UNCLASSIFIED),
        Model("KS-F18", Provenance.UNCLASSIFIED),
        Model("KS-F2", Provenance.UNCLASSIFIED),
        Model("KS-F22", Provenance.UNCLASSIFIED),
        Model("KS-F22P", Provenance.UNCLASSIFIED),
    )

    /** Mirrors DeviceBleBean.getModel() substring gating, with longer names taking priority. */
    fun identify(modelText: String?): Model? {
        if (modelText.isNullOrBlank()) return null
        val normalized = modelText.uppercase().replace('_', '-').replace(Regex("\\s+"), " ")
        if ("KS18L4" in normalized) return models.first { it.name == "KS-18XL" }
        return models.asSequence().sortedByDescending { it.name.length }
            .firstOrNull { model -> model.name in normalized || model.name.removePrefix("KS-") in normalized }
    }
}
