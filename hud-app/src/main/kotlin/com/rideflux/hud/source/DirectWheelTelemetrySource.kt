/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.source

import android.util.Log
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.repository.WheelRepository
import com.rideflux.domain.wheel.WheelFamily
import com.rideflux.hud.SignalQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext

/**
 * Original behaviour: open a wheel GATT directly from the glasses.
 * Used when no MAC-based bridge is available, e.g. a stand-alone HUD
 * test or when the phone is absent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectWheelTelemetrySource(
    private val wheelRepository: WheelRepository,
    private val mac: String,
    private val family: WheelFamily,
) : HudTelemetrySource {

    override fun frames(): Flow<HudTelemetryFrame> {
        return flow {
            val conn = withContext(Dispatchers.IO) {
                wheelRepository.connect(address = mac, expectedFamily = family)
            }
            var reachedActiveState = false
            try {
                combine(conn.state, conn.telemetry) { state, telemetry ->
                    HudTelemetryFrame(
                        state = state,
                        telemetry = telemetry,
                        signal = signalFromState(state),
                        staleHint = false,
                        phoneBatteryPercent = null,
                    )
                }.transformWhile { frame ->
                    emit(frame)
                    when (frame.state) {
                        is ConnectionState.Failed -> false
                        ConnectionState.Disconnected -> !reachedActiveState
                        ConnectionState.Connecting,
                        is ConnectionState.Handshaking,
                        ConnectionState.Ready,
                        -> {
                            reachedActiveState = true
                            true
                        }
                    }
                }.collect { emit(it) }
                error("direct wheel link ended")
            } finally {
                withContext(NonCancellable) {
                    try { conn.close() } catch (_: Throwable) { /* best-effort */ }
                }
            }
        }.retryWhen { cause, attempt ->
            if (attempt >= MAX_CONNECT_RETRIES) return@retryWhen false
            Log.i(TAG, "connect attempt ${attempt + 1} failed: ${cause.message}")
            delay((500L * (attempt + 1)).coerceAtMost(MAX_BACKOFF_MILLIS))
            true
        }
    }

    private fun signalFromState(state: ConnectionState): SignalQuality = when (state) {
        ConnectionState.Ready -> SignalQuality.GOOD
        is ConnectionState.Handshaking, ConnectionState.Connecting -> SignalQuality.WEAK
        ConnectionState.Disconnected, is ConnectionState.Failed -> SignalQuality.NONE
    }

    private companion object {
        const val TAG = "DirectWheelTelemetrySource"
        const val MAX_CONNECT_RETRIES: Long = 5L
        const val MAX_BACKOFF_MILLIS: Long = 5_000L
    }
}
