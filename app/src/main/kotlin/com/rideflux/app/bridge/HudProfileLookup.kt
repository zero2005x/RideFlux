package com.rideflux.app.bridge

import com.rideflux.domain.settings.HudLayoutProfile

/** A paired token takes precedence over a legacy MAC when both identify one pair of glasses. */
internal fun resolveHudProfile(
    id: String,
    profiles: Map<String, HudLayoutProfile>,
    approved: List<ApprovedGlasses> = emptyList(),
): HudLayoutProfile {
    val token = approved.firstOrNull { it.mac?.replace(":", "").equals(id, ignoreCase = true) }?.tokenHex
    return token?.let(profiles::get) ?: profiles[id] ?: HudLayoutProfile()
}
