/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import com.rideflux.data.bridge.BridgeFrame
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Keeps the glasses fed for as long as the bridge runs, even if the pipeline behind it dies.
 *
 * The frame pipeline is meant to never end, and its inner loop already survives every wheel-link
 * failure. What it cannot survive is an unexpected exception from anywhere else in it. The
 * previous handler answered that by switching to standby frames for good, so one stray exception
 * left the HUD "waiting for the vehicle" until the rider toggled the bridge by hand: at 12:50 on
 * 2026-10-08 it stayed that way for ten minutes. Now a failure is reported through [onFailure],
 * standby frames keep the glasses' link alive for [backoffMillis], and the pipeline is built
 * again. A run that stayed up for [healthyAfterMillis] counts as recovered, so a rare failure
 * never inherits the long wait of an earlier burst.
 *
 * Only the pipeline's own failures are retried. Cancellation always passes through, and an
 * exception thrown by the collector (for example the publisher going away) is not the pipeline's
 * to catch, which is exactly what [catch] guarantees.
 */
internal fun Flow<BridgeFrame>.restartingOnFailure(
    backoffMillis: (attempt: Long) -> Long,
    healthyAfterMillis: Long,
    elapsedRealtime: () -> Long,
    onFailure: (Throwable) -> Unit,
    standbyFor: suspend FlowCollector<BridgeFrame>.(waitMillis: Long) -> Unit,
): Flow<BridgeFrame> {
    val pipeline = this
    return flow {
        var attempt = 0L
        while (currentCoroutineContext().isActive) {
            val startedAt = elapsedRealtime()
            emitAll(
                pipeline.catch { error ->
                    if (error is CancellationException) throw error
                    onFailure(error)
                },
            )
            if (elapsedRealtime() - startedAt >= healthyAfterMillis) attempt = 0L
            standbyFor(backoffMillis(attempt++))
        }
    }
}
