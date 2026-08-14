/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.source

import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.hud.SignalQuality
import com.rideflux.hud.BridgeLinkState
import kotlinx.coroutines.flow.Flow

/**
 * Per-frame projection consumed by [com.rideflux.hud.HudViewModel].
 * Identical shape regardless of whether the underlying transport is
 * a direct wheel BLE link or the phone-bridge relay so the
 * ViewModel's projection logic stays a single code path.
 *
 * Invariants:
 *  - direct-mode frames MUST leave [phoneBatteryPercent] null;
 *  - a frame with [staleHint] or [SignalQuality.NONE] is NOT a
 *    healthy connected frame, even when [state] reports Ready.
 */
data class HudTelemetryFrame(
    val state: ConnectionState,
    val telemetry: WheelTelemetry,
    /**
     * Coarse signal hint, propagated from the source. The bridge
     * source uses the wire-format signal byte directly; the direct
     * source derives it from [state].
     */
    val signal: SignalQuality,
    /**
     * True when the underlying frame is older than the staleness
     * threshold. The bridge source decides this on the phone side
     * (since it sees the unwrapped timestamp) and forwards the bit;
     * the direct source defers to the ViewModel as before.
     */
    val staleHint: Boolean,
    /**
     * Optional phone battery, populated only by the bridge source.
     * Direct mode leaves this null.
     */
    val phoneBatteryPercent: Int? = null,
    /** Bridge-only phone/wheel availability. Null for direct-wheel mode. */
    val bridgeLinkState: BridgeLinkState? = null,
)

/**
 * Pluggable upstream for the HUD ViewModel. Two impls live alongside:
 *  - [DirectWheelTelemetrySource] — owns the wheel GATT directly
 *    (single-central; competes with the phone).
 *  - [BridgeTelemetrySource] — receives pre-decoded frames from the
 *    phone over the [com.rideflux.data.bridge] GATT relay.
 */
interface HudTelemetrySource {
    /**
     * Hot stream of HUD frames; cancelled when the consumer scope
     * dies. Implementations MUST return a hot flow (single emission
     * shared across collectors, e.g. a SharedFlow/stateIn) — a cold
     * Flow implementation would silently re-run the underlying BLE
     * work per collector. Every source emits one frame per telemetry
     * update and MUST keep emitting (or complete with a terminal
     * Disconnected/NONE frame) so the UI never stalls on a stale
     * frame.
     */
    fun frames(): Flow<HudTelemetryFrame>
}
