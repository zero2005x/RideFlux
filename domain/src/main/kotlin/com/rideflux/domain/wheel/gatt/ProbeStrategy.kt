/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.domain.wheel.gatt

import com.rideflux.domain.wheel.WheelFamily
import java.util.UUID

/**
 * The link a probe would be written to: the service and the two
 * characteristics the central reads notifications from and writes to.
 */
data class ProbeLink(
    val service: UUID,
    val notifyCharacteristic: UUID,
    val writeCharacteristic: UUID,
)

/** What a probe concluded. */
data class ProbeResult(
    val family: WheelFamily,
    val generation: Int? = null,
    /** Which note or capture justifies this conclusion. */
    val evidence: String,
)

/**
 * Active disambiguation, defined here so T07/T08 can plug implementations in.
 *
 * **Nothing sends a probe in T02.** The interface exists because the passive
 * path has a hard limit that the findings state plainly: Nordic UART is shared
 * by Inmotion V2, Ninebot Z, **VESC** and (since 2026-10-07) SoFlow, so no GATT
 * signature — however well built — can separate them
 * (`GATT_FINGERPRINT_IDENTIFICATION.md` §1, §7.4). The strongest available
 * discriminator for that collision is one *read*: a VESC answers `GetPkgInfo`
 * (marker `0x65 0x00`) and the others do not (`:5bis`).
 *
 * Design rules this interface encodes, taken from that section:
 *
 *  1. **Signatures stay first.** They need no radio traffic and no
 *     authorisation; a probe is only ever the tie-breaker for a signature set
 *     that matched more than one family.
 *  2. **A probe is a read.** `GetPkgInfo` changes nothing, so it does not touch
 *     any write gate — which is why identification may use it while every
 *     `WheelCommand` write stays `Unsupported`.
 *  3. **A probe answers with evidence, not with a boolean.** [ProbeResult]
 *     carries the note that justifies the attribution, so the result can be
 *     audited the same way a signature row is.
 *
 * Implementations must be pure with respect to I/O: they build bytes and
 * interpret bytes; sending them is the transport's job.
 */
interface ProbeStrategy {

    /** Stable id, used in logs and in the report that justifies the probe. */
    val id: String

    /** `true` when [link] can carry this probe (e.g. the VESC probe needs NUS). */
    fun supports(link: ProbeLink): Boolean

    /** The exact bytes to write. Only called when [supports] returned `true`. */
    fun request(): ByteArray

    /**
     * Interpret one notification.
     *
     * @return the conclusion, or `null` when this notification is not a reply
     *   to this probe (or is too short to tell) — never a guess.
     */
    fun interpret(notification: ByteArray): ProbeResult?
}
