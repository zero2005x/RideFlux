/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.source

import android.util.Log
import com.rideflux.data.bridge.BridgeCodec
import com.rideflux.data.bridge.BridgeFrame
import com.rideflux.data.bridge.BridgeProtocol
import com.rokid.cxr.CXRServiceBridge
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide receiver for the official Rokid CXR glasses channel. */
internal object RokidCxrBridgeClient {
    private val receivedFrames = MutableSharedFlow<BridgeFrame>(
        // CXR publishes at 20 Hz, so replaying an old frame after a HUD
        // collector restart is worse than waiting at most 50 ms for the
        // next live sample.
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val receivedSinceConnection = AtomicBoolean(false)
    private val invalidPayloadLogged = AtomicBoolean(false)

    @Volatile private var started = false
    private var bridge: CXRServiceBridge? = null

    fun frames(): Flow<BridgeFrame> {
        return if (ensureStarted()) {
            receivedFrames.asSharedFlow()
        } else {
            // Surface startup failure instead of returning a silent SharedFlow
            // that never completes. BridgeTelemetrySource will retry us.
            flow { throw IllegalStateException("Rokid CXR subscription unavailable") }
        }
    }

    @Synchronized
    private fun ensureStarted(): Boolean {
        if (started) return true
        try {
            val candidate = CXRServiceBridge()
            candidate.setStatusListener(
                object : CXRServiceBridge.StatusListener {
                    // cxr-service-bridge 1.0 API: onConnected carries the
                    // peer name, the Rokid account id and a device-type int.
                    override fun onConnected(deviceName: String?, account: String?, type: Int) {
                        receivedSinceConnection.set(false)
                        invalidPayloadLogged.set(false)
                        Log.i(
                            TAG,
                            "Rokid CXR phone connected: name=$deviceName type=$type" +
                                if (account.isNullOrBlank()) "" else " account=${account.take(4)}…",
                        )
                    }

                    override fun onConnecting(deviceName: String?, account: String?, type: Int) {
                        Log.d(TAG, "Rokid CXR phone connecting: name=$deviceName type=$type")
                    }

                    override fun onDisconnected() {
                        receivedSinceConnection.set(false)
                        Log.i(TAG, "Rokid CXR phone disconnected")
                    }

                    override fun onARTCStatus(health: Float, reset: Boolean) = Unit

                    override fun onRokidAccountChanged(account: String?) {
                        Log.d(TAG, "Rokid CXR account changed")
                    }
                },
            )
            val result = candidate.subscribe(BridgeProtocol.CXR_TELEMETRY_CHANNEL) { _, _, value ->
                val frame = value?.let(BridgeCodec::decode)
                if (frame == null) {
                    if (invalidPayloadLogged.compareAndSet(false, true)) {
                        Log.w(TAG, "discarding invalid CXR telemetry payload size=${value?.size ?: 0}")
                    }
                    return@subscribe
                }
                if (receivedSinceConnection.compareAndSet(false, true)) {
                    Log.i(TAG, "first Rokid CXR telemetry frame received")
                }
                receivedFrames.tryEmit(frame)
            }
            if (result != 0) {
                Log.w(TAG, "Rokid CXR subscribe returned $result")
                return false
            }
            bridge = candidate
            started = true
            Log.i(TAG, "Rokid CXR telemetry channel subscribed")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "Rokid CXR receiver unavailable: ${t.message}")
            return false
        }
    }

    private const val TAG = "RokidCxrClient"
}
