/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.protocol.familyg

import com.rideflux.domain.telemetry.BmsFrame

/**
 * Experimental, passive BMS envelope parser; no physical payload interpretation yet.
 * L2 envelope: findings/BEGODE_A2_TRUTH_TABLE.md:27-38 (also owner CAP-A).
 * L2 type inventory: findings/CROSS_BRAND_COMMAND_REFERENCE.md:236-250.
 * Type 1 conflicts: BEGODE_A2_TRUTH_TABLE.md:52-56,112-139 versus
 * CROSS_BRAND_COMMAND_REFERENCE.md:227. Cell-page scaling/count are not established;
 * begode-frame-conflicts.md:114-123 is superseded and type 5 is disputed.
 */
object BegodeBmsDecoder {
    // L2 vendor-static routing inventory; these codes do not authorise payload decoding.
    private val BMS_TYPES = setOf(0x01, 0x02, 0x03, 0x05, 0x06)

    fun decode(bytes: ByteArray): BegodeBmsResult {
        val envelope = BegodeDecoder.decode(bytes)
            ?: return BegodeBmsResult.Malformed
        if (envelope !is BegodeFrame.Unknown || envelope.typeCode !in BMS_TYPES) {
            return BegodeBmsResult.Unsupported
        }
        return BegodeBmsResult.Raw(
            BmsFrame.Raw(envelope.typeCode, envelope.subIndex, bytes.toList()),
        )
    }
}

sealed interface BegodeBmsResult {
    data class Raw(val frame: BmsFrame.Raw) : BegodeBmsResult
    data object Malformed : BegodeBmsResult
    data object Unsupported : BegodeBmsResult
}
