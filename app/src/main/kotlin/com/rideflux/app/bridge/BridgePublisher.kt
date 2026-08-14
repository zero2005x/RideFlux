/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.content.Context
import com.rideflux.data.bridge.BridgeFrame
import com.rideflux.data.bridge.BridgeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** Output side of the phone bridge; wheel acquisition stays transport-agnostic. */
internal interface BridgePublisher {
    fun open(): Boolean
    fun attachSource(scope: CoroutineScope, source: Flow<BridgeFrame>)
    fun stop()
    fun setLowLatency(enabled: Boolean) = Unit
}

internal class NativeBleBridgePublisher(
    context: Context,
    private val onState: (GlassesLinkState) -> Unit,
) : BridgePublisher {
    private val server = BridgeServer(context) { connected ->
        onState(if (connected) GlassesLinkState.CONNECTED else GlassesLinkState.READY)
    }

    override fun open(): Boolean {
        onState(GlassesLinkState.STARTING)
        val opened = server.open()
        onState(if (opened) GlassesLinkState.READY else GlassesLinkState.ERROR)
        return opened
    }

    override fun attachSource(scope: CoroutineScope, source: Flow<BridgeFrame>) =
        server.attachSource(scope, source)

    override fun stop() {
        server.stop()
        onState(GlassesLinkState.STOPPED)
    }

    override fun setLowLatency(enabled: Boolean) {
        if (!server.setAdvertiseMode(enabled)) onState(GlassesLinkState.ERROR)
    }
}
