/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.wheel

/**
 * How much of a device's identity the codec has actually established.
 *
 * A family hint from an advertisement is **not** an identity. The levels are
 * deliberately ordered from weakest to strongest, and a consumer must not
 * promote a value: only the codec that decoded the handshake can say that it
 * saw a model string or a firmware version.
 */
enum class WheelIdentityEvidence {
    /** The protocol family is known, but the model string was absent or not recognised. */
    FAMILY_ONLY,

    /** An exact model key was recognised, but no usable firmware version was decoded. */
    MODEL_ONLY,

    /** An exact model key and a decoded firmware version are both available. */
    MODEL_AND_FIRMWARE,
}

/**
 * Which telemetry fields this session is allowed to publish.
 *
 * The ordering is not a claim that every field in a scope is implemented for
 * every model; it says which *evidence class* the decoder is operating under.
 * `MODEL_LAYOUT` still means "only the fields the model layout establishes",
 * and every field outside that layout stays `null`.
 */
enum class WheelTelemetryCoverage {
    /** Nothing beyond the connection lifecycle is decoded. */
    NONE,

    /** Only the fields whose meaning is constant across the family (I2: voltage/current). */
    CORE_ELECTRICAL,

    /** Core fields plus a family-wide fault record whose offset is established for this variant. */
    CORE_AND_FAULTS,

    /** Core fields plus a decoded per-model real-time layout. */
    MODEL_LAYOUT,
}

/**
 * What the session is allowed to control.
 *
 * This is a coarse gate; [WheelCapabilities] remains the per-feature matrix.
 * A session with [NONE] must not advertise any writable capability even if the
 * protocol family as a whole has a command encoder.
 */
enum class WheelControlContract {
    /** No write contract is established for this identity; every control is unsupported. */
    NONE,

    /** The family's closed command set is available for a recognised model + firmware. */
    FAMILY_CLOSED,
}

/**
 * Evidence-backed support statement published with [WheelIdentity].
 *
 * This is intentionally separate from [WheelCapabilities]:
 *
 *  * [WheelCapabilities] answers "which controls may this session offer?";
 *  * [WheelSupport] answers "how much of the device do we actually know?".
 *
 * UI code must show a limited-support state whenever [isLimited] is true,
 * even if the protocol layer can still report `Ready`. A `Ready` connection
 * is not a claim that the model is fully supported.
 */
data class WheelSupport(
    val identityEvidence: WheelIdentityEvidence,
    val telemetryCoverage: WheelTelemetryCoverage,
    val controlContract: WheelControlContract,
    /** Short machine-readable reason for a downgrade, e.g. `model-unknown`. */
    val reason: String? = null,
    /** Human-readable limitation, used by the support banner. */
    val detail: String? = null,
    /**
     * False when a model-specific layout is implemented only partially (for
     * example V11Y publishes speed/battery but not trip/temperature), or when
     * only the family core is available. A recognised model+firmware with a
     * complete layout is the only case that may render without a support
     * notice.
     */
    val telemetryComplete: Boolean = true,
) {
    /**
     * True when this session is not a fully identified model+firmware session
     * with a closed family control contract. A limited session may still be
     * `Ready` and may still stream some telemetry.
     */
    val isLimited: Boolean
        get() = identityEvidence != WheelIdentityEvidence.MODEL_AND_FIRMWARE ||
            controlContract != WheelControlContract.FAMILY_CLOSED ||
            !telemetryComplete

    companion object {
        /** A session that has not published identification evidence yet. */
        val UNKNOWN: WheelSupport = WheelSupport(
            identityEvidence = WheelIdentityEvidence.FAMILY_ONLY,
            telemetryCoverage = WheelTelemetryCoverage.NONE,
            controlContract = WheelControlContract.NONE,
            reason = "unidentified",
            detail = "Device identification has not completed.",
        )

        /** Family known, model/firmware unknown: read-only core telemetry at most. */
        val FAMILY_ONLY: WheelSupport = WheelSupport(
            identityEvidence = WheelIdentityEvidence.FAMILY_ONLY,
            telemetryCoverage = WheelTelemetryCoverage.CORE_ELECTRICAL,
            controlContract = WheelControlContract.NONE,
            reason = "model-unknown",
            detail = "Model not recognised; controls are disabled and telemetry is core-only.",
        )
    }
}
