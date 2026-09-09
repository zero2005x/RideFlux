/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.source

import android.content.Context
import android.util.Log
import com.rideflux.data.bridge.BridgeClient
import com.rideflux.data.bridge.BridgeFrame
import com.rideflux.data.bridge.BridgePeerFilter
import com.rideflux.data.bridge.SignalLevel
import com.rideflux.domain.connection.ConnectionState
import com.rideflux.domain.telemetry.WheelTelemetry
import com.rideflux.hud.BridgeLinkState
import com.rideflux.hud.SignalQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Receives bridge frames from either the official Rokid CXR transport or
 * Android's native BLE transport. CXR wins while it is producing fresh data;
 * BLE remains available as the compatibility fallback.
 */
class BridgeTelemetrySource private constructor(
    private val clientFrames: () -> Flow<BridgeFrame>,
    private val rokidFrames: (() -> Flow<BridgeFrame>)?,
) : HudTelemetrySource {

    constructor(context: Context, pairedPhoneToken: ByteArray?, pairedPhoneMac: String?) : this(
        clientFrames = BridgeClient(
            context.applicationContext,
            peerFilterFor(pairedPhoneToken, pairedPhoneMac),
        )
            .let { client -> { client.frames() } },
        rokidFrames = { RokidCxrBridgeClient.frames() },
    )

    @Suppress("UNUSED_PARAMETER")
    internal constructor(clientFrames: () -> Flow<BridgeFrame>, testOnly: Unit) :
        this(clientFrames = clientFrames, rokidFrames = null)

    @Suppress("UNUSED_PARAMETER")
    internal constructor(
        clientFrames: () -> Flow<BridgeFrame>,
        rokidFrames: () -> Flow<BridgeFrame>,
        testOnly: Unit,
    ) : this(clientFrames = clientFrames, rokidFrames = rokidFrames)

    override fun frames(): Flow<HudTelemetryFrame> = channelFlow {
        // Give the preferred CXR path an exclusive startup window. Once a
        // CXR frame arrives, collectLatest below cancels BridgeClient, whose
        // awaitClose immediately releases its scan/GATT. Native BLE starts
        // only after CXR has gone quiet long enough to be a real fallback.
        val cxrPreferred = MutableStateFlow(rokidFrames != null)
        var cxrExpiryJob: Job? = rokidFrames?.let {
            launch {
                delay(CXR_STARTUP_GRACE_MILLIS)
                cxrPreferred.value = false
            }
        }
        send(noPhoneFrame())

        rokidFrames?.let { source ->
            launch {
                while (true) {
                    try {
                        source().collect { frame ->
                            cxrPreferred.value = true
                            cxrExpiryJob?.cancel()
                            cxrExpiryJob = launch {
                                delay(CXR_FRESHNESS_MILLIS)
                                cxrPreferred.value = false
                            }
                            send(frame.toHudTelemetryFrame())
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.w(TAG, "Rokid CXR bridge lost: ${error.message}")
                    }
                    delay(CXR_RECONNECT_DELAY_MILLIS)
                }
            }
        }

        launch {
            cxrPreferred.collectLatest { suppressNative ->
                if (suppressNative) return@collectLatest

                var attempt = 0
                while (true) {
                    try {
                        clientFrames().collect { frame ->
                            attempt = 0
                            send(frame.toHudTelemetryFrame())
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.w(TAG, "native BLE bridge lost: ${error.message}")
                    }
                    attempt += 1
                    if (!cxrPreferred.value) {
                        send(noPhoneFrame())
                    }
                    val delayMs = (NATIVE_RETRY_BASE_MILLIS shl (attempt - 1).coerceAtMost(NATIVE_RETRY_MAX_SHIFT))
                        .coerceAtMost(NATIVE_RETRY_MAX_MILLIS)
                    delay(delayMs)
                }
            }
        }

        awaitCancellation()
    }

    private fun noPhoneFrame() = HudTelemetryFrame(
        state = ConnectionState.Connecting,
        telemetry = WheelTelemetry.EMPTY,
        signal = SignalQuality.NONE,
        staleHint = true,
        phoneBatteryPercent = null,
        bridgeLinkState = BridgeLinkState.NO_PHONE,
    )

    private companion object {
        const val TAG: String = "BridgeTelemetrySource"
        const val CXR_RECONNECT_DELAY_MILLIS = 1_000L
        const val CXR_FRESHNESS_MILLIS = 2_500L
        const val CXR_STARTUP_GRACE_MILLIS = 3_000L

        /**
         * First native-BLE retry delay. Each attempt runs one BLE scan,
         * and the platform silently mutes an app that starts more than
         * five scans in 30 seconds, so retries start a full second apart
         * rather than the 250 ms that used to exhaust the budget within
         * half a minute. BleScanThrottle enforces the hard limit;
         * this backoff keeps the loop from leaning on it.
         */
        const val NATIVE_RETRY_BASE_MILLIS = 1_000L
        const val NATIVE_RETRY_MAX_MILLIS = 15_000L
        const val NATIVE_RETRY_MAX_SHIFT = 4
    }
}

/**
 * Identity check for the phone bridge, in order of preference.
 *
 * A stored pairing token is the only thing that keeps matching once
 * Android rotates the advertising address, so it wins whenever one
 * exists. A stored MAC means the rider paired before tokens shipped:
 * honour it so the HUD keeps working until they re-pair, but it will
 * stop matching on the next address rotation. Failing both, a debug
 * build stays permissive for bring-up and a release build refuses —
 * an unverified peer can feed the HUD fabricated telemetry.
 */
internal fun peerFilterFor(
    pairedPhoneToken: ByteArray?,
    pairedPhoneMac: String?,
): BridgePeerFilter = when {
    pairedPhoneToken != null -> BridgePeerFilter.PairingToken(pairedPhoneToken)
    pairedPhoneMac != null -> BridgePeerFilter.Allowlist(setOf(pairedPhoneMac))
    com.rideflux.hud.BuildConfig.DEBUG -> BridgePeerFilter.AcceptAny
    else -> BridgePeerFilter.RejectAll
}

internal fun BridgeFrame.toHudTelemetryFrame(): HudTelemetryFrame {
    val telemetry = WheelTelemetry(
        timestampMillis = timestampMillis,
        speedKmh = speedKmh,
        voltageV = voltageV,
        batteryPercent = vehicleBatteryPercent,
        tripDistanceMetres = tripDistanceMetres,
    )
    val state: ConnectionState = when {
        !ready || stale || signal == SignalLevel.NONE -> ConnectionState.Connecting
        else -> ConnectionState.Ready
    }
    return HudTelemetryFrame(
        state = state,
        telemetry = telemetry,
        signal = when (signal) {
            SignalLevel.GOOD -> SignalQuality.GOOD
            SignalLevel.WEAK -> SignalQuality.WEAK
            SignalLevel.NONE -> SignalQuality.NONE
        },
        staleHint = stale,
        phoneBatteryPercent = phoneBatteryPercent,
        bridgeLinkState = if (state == ConnectionState.Ready) {
            BridgeLinkState.WHEEL_LIVE
        } else {
            BridgeLinkState.PHONE_STANDBY
        },
    )
}
