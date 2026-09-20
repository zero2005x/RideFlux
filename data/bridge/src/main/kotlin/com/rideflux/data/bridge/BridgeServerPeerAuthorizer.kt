/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import android.bluetooth.BluetoothDevice
import android.util.Log
import java.util.Locale

/**
 * Decides which central the phone will stream telemetry to.
 *
 * The mirror image of [BridgePeerFilter], which is the glasses deciding
 * whose frames to trust. Both ends need a decision: the bridge service
 * UUID is public, the GATT server is connectable, and a subscription is
 * unauthenticated by default.
 *
 * Centrals identify themselves by writing their 8-byte token to
 * [BridgeProtocol.HANDSHAKE_CHAR_UUID] upon connection. Centrals that
 * have been approved by the user are authorized to subscribe; unknown
 * centrals enter a pending state awaiting user confirmation.
 */
fun interface BridgeServerPeerAuthorizer {

    /**
     * True when [device] with [handshakeToken] is authorized to receive
     * telemetry notifications.
     *
     * @param device the central requesting to subscribe.
     * @param handshakeToken the 8-byte token written by the central to
     *   [BridgeProtocol.HANDSHAKE_CHAR_UUID], or null for legacy centrals.
     */
    fun isAuthorized(device: BluetoothDevice, handshakeToken: ByteArray?): Boolean

    companion object {
        private const val TAG = "BridgeServerPeer"

        /**
         * Accept every central.
         *
         * Only for debug builds and bring-up; logs a warning on each check.
         */
        val AcceptAny: BridgeServerPeerAuthorizer = BridgeServerPeerAuthorizer { device, _ ->
            Log.w(
                TAG,
                "accepting unverified peer ${device.address} (AcceptAny) — telemetry stream exposed",
            )
            true
        }

        /** Reject every central by default; forces all peers to request approval. */
        val RejectAll: BridgeServerPeerAuthorizer = BridgeServerPeerAuthorizer { _, _ -> false }

        /**
         * Authorize centrals matching approved tokens or approved legacy MAC addresses.
         */
        fun fromApproved(
            approvedTokens: () -> Set<String>,
            approvedMacs: () -> Set<String> = { emptySet() },
        ): BridgeServerPeerAuthorizer = FromApproved(approvedTokens, approvedMacs)
    }

    class FromApproved(
        private val approvedTokens: () -> Set<String>,
        private val approvedMacs: () -> Set<String>,
    ) : BridgeServerPeerAuthorizer {

        override fun isAuthorized(device: BluetoothDevice, handshakeToken: ByteArray?): Boolean =
            isAuthorizedPeer(device.address, handshakeToken)

        /**
         * Decoupled from [BluetoothDevice] so the authorization logic can be
         * unit-tested without framework dependencies.
         */
        internal fun isAuthorizedPeer(address: String?, handshakeToken: ByteArray?): Boolean {
            if (handshakeToken != null) {
                val hex = BridgePairingToken.toHex(handshakeToken)
                val tokens = approvedTokens()
                if (tokens.any { it.equals(hex, ignoreCase = true) }) {
                    return true
                }
            }
            if (address != null) {
                val mac = address.trim().uppercase(Locale.ROOT)
                val macs = approvedMacs()
                if (macs.any { it.trim().uppercase(Locale.ROOT) == mac }) {
                    return true
                }
            }
            return false
        }
    }
}
