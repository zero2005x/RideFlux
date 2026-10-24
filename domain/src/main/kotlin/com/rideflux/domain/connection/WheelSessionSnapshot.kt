package com.rideflux.domain.connection

import com.rideflux.domain.wheel.WheelCapabilities
import com.rideflux.domain.wheel.WheelIdentity
import com.rideflux.domain.wheel.WheelSupport

/**
 * One immutable publication of the identity facts that belong together.
 *
 * The separate [WheelConnection.identity] and [WheelConnection.support] flows
 * are retained for compatibility, but a decision that persists an identity
 * must use one snapshot so a new identity can never borrow a previous
 * publication's evidence grade.
 *
 * [sequence] is monotonic within a connection session; consumers should ignore
 * snapshots older than the last one they acted on. The connection instance is
 * the session/epoch boundary.
 */
data class WheelSessionSnapshot(
    val sequence: Long,
    val identity: WheelIdentity?,
    val capabilities: WheelCapabilities?,
    val support: WheelSupport?,
) {
    val identityEvidence get() = support?.identityEvidence
}
