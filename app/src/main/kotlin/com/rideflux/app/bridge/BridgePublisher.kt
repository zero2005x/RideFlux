/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.content.Context
import com.rideflux.app.BuildConfig
import com.rideflux.data.bridge.BridgeFrame
import com.rideflux.data.bridge.BridgeServer
import com.rideflux.data.bridge.BridgeServerPeerAuthorizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** Data class representing a pending authorization request from glasses. */
data class GlassesAuthorizationRequest(
    val deviceAddress: String,
    val token: ByteArray?,
    val shortCode: String,
    val isLegacy: Boolean,
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GlassesAuthorizationRequest) return false
        return deviceAddress == other.deviceAddress &&
            ((token == null && other.token == null) ||
                (token != null && other.token != null && token.contentEquals(other.token)))
    }

    override fun hashCode(): Int =
        deviceAddress.hashCode() * 31 + (token?.contentHashCode() ?: 0)
}

/** Output side of the phone bridge; wheel acquisition stays transport-agnostic. */
internal interface BridgePublisher {
    suspend fun open(): Boolean
    fun attachSource(scope: CoroutineScope, source: Flow<BridgeFrame>)
    fun stop()
    fun setLowLatency(enabled: Boolean) = Unit
    fun approvePeer(address: String): Boolean = false
    fun rejectPeer(address: String): Boolean = false
}

internal class NativeBleBridgePublisher(
    context: Context,
    private val onState: (GlassesLinkState) -> Unit,
    private val onAuthorizationRequested: (GlassesAuthorizationRequest) -> Unit = {},
    private val onAuthorizationDismissed: (deviceAddress: String) -> Unit = {},
) : BridgePublisher {
    private val authorizer: BridgeServerPeerAuthorizer = if (BuildConfig.DEBUG) {
        BridgeServerPeerAuthorizer.AcceptAny
    } else {
        BridgeServerPeerAuthorizer.fromApproved(
            approvedTokens = {
                ApprovedGlassesStore.getAll(context)
                    .mapNotNull { it.tokenHex }
                    .toSet()
            },
            approvedMacs = {
                ApprovedGlassesStore.getAll(context)
                    .mapNotNull { it.mac }
                    .toSet()
            },
        )
    }

    // Advertised as service data so the glasses recognise this phone
    // across BLE address rotation; see BridgePairingStore.
    private val server = BridgeServer(
        context = context,
        pairingToken = BridgePairingStore.readOrCreate(context),
        peerAuthorizer = authorizer,
        onSubscriberStateChanged = { connected ->
            onState(if (connected) GlassesLinkState.CONNECTED else GlassesLinkState.READY)
        },
        onAuthorizationRequested = { device, token ->
            val shortCode = token?.let(com.rideflux.data.bridge.BridgePairingToken::shortCode)
                ?: device.address?.replace(":", "")?.takeLast(4)?.uppercase(java.util.Locale.ROOT)
                ?: "????"
            val request = GlassesAuthorizationRequest(
                deviceAddress = device.address.orEmpty(),
                token = token,
                shortCode = shortCode,
                isLegacy = token == null,
            )
            onAuthorizationRequested(request)
        },
        onAuthorizationTimedOut = { device ->
            onAuthorizationDismissed(device.address.orEmpty())
        },
    )

    override fun approvePeer(address: String): Boolean = server.approvePeer(address)

    override fun rejectPeer(address: String): Boolean = server.rejectPeer(address)

    override suspend fun open(): Boolean {
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
