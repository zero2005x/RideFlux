/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.Locale

/**
 * Decides which advertiser [BridgeClient] is willing to connect to.
 *
 * A BLE service UUID is public and trivially spoofable, so matching on
 * [BridgeProtocol.SERVICE_UUID] alone means any nearby device can feed
 * the HUD fabricated speed and battery readings. This type makes the
 * trust decision explicit and enforced in code rather than left as a
 * comment.
 *
 * [PairingToken] is the production choice: it matches the token the
 * phone advertises as service data, which — unlike the scanned MAC
 * address [Allowlist] used to key on — survives BLE address rotation.
 * See [BridgeProtocol.PAIRING_TOKEN_SIZE].
 */
sealed class BridgePeerFilter {

    /**
     * True if the advertiser may be connected to.
     *
     * @param device the advertising peer.
     * @param advertisedToken service data published under
     *   [BridgeProtocol.SERVICE_UUID], or `null` when the advertiser
     *   published none (an older phone build, or an unrelated device).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    abstract fun accepts(device: BluetoothDevice, advertisedToken: ByteArray?): Boolean

    /**
     * Accept the first advertiser of the bridge service UUID.
     *
     * Insecure: retained for debug builds and for bring-up before a
     * phone has been paired. Every acceptance is logged at WARN so the
     * exposure is visible in a bug report.
     */
    data object AcceptAny : BridgePeerFilter() {
        override fun accepts(device: BluetoothDevice, advertisedToken: ByteArray?): Boolean {
            Log.w(
                TAG,
                "accepting unverified bridge peer ${device.address} " +
                    "(BridgePeerFilter.AcceptAny) — telemetry may be spoofed",
            )
            return true
        }
    }

    /** Reject every advertiser until an explicit phone pairing has been stored. */
    data object RejectAll : BridgePeerFilter() {
        override fun accepts(device: BluetoothDevice, advertisedToken: ByteArray?): Boolean {
            Log.w(TAG, "rejecting bridge peer ${device.address}: no paired phone")
            return false
        }
    }

    /**
     * Accept only the phone whose advertised pairing token matches the
     * one stored when the rider paired.
     *
     * This is the identity check that replaced [Allowlist]: the token
     * is minted once per phone install and never changes, so nothing
     * breaks when the controller rotates the advertising address.
     */
    class PairingToken(token: ByteArray) : BridgePeerFilter() {

        private val expected: ByteArray = token.copyOf()

        init {
            require(expected.size == BridgeProtocol.PAIRING_TOKEN_SIZE) {
                "pairing token must be ${BridgeProtocol.PAIRING_TOKEN_SIZE} bytes"
            }
        }

        override fun accepts(device: BluetoothDevice, advertisedToken: ByteArray?): Boolean {
            val ok = acceptsToken(advertisedToken)
            if (!ok) {
                Log.w(
                    TAG,
                    "rejecting bridge peer ${device.address}: pairing token mismatch " +
                        "(advertised=${advertisedToken?.let(BridgePairingToken::toHex) ?: "none"})",
                )
            }
            return ok
        }

        /** Token comparison on its own, so it stays unit-testable off-device. */
        internal fun acceptsToken(advertisedToken: ByteArray?): Boolean =
            advertisedToken != null && advertisedToken.contentEquals(expected)

        override fun equals(other: Any?): Boolean =
            this === other || (other is PairingToken && expected.contentEquals(other.expected))

        override fun hashCode(): Int = expected.contentHashCode()

        override fun toString(): String =
            "PairingToken(${BridgePairingToken.shortCode(expected)}…)"
    }

    /**
     * Accept only devices whose MAC is in [addresses] (case-insensitive).
     *
     * Legacy: kept so a HUD that paired before pairing tokens existed
     * keeps working until the rider re-pairs. Do not use for new
     * pairings — Android rotates the advertising address, so a stored
     * MAC stops matching within minutes. Prefer [PairingToken].
     */
    data class Allowlist(private val addresses: Set<String>) : BridgePeerFilter() {
        private val normalised: Set<String> =
            addresses.mapTo(mutableSetOf()) { it.trim().uppercase(Locale.ROOT) }

        init {
            require(normalised.isNotEmpty()) { "allowlist must not be empty" }
        }

        override fun accepts(device: BluetoothDevice, advertisedToken: ByteArray?): Boolean {
            val ok = acceptsAddress(device.address)
            if (!ok) Log.w(TAG, "rejecting bridge peer ${device.address}: not in allowlist")
            return ok
        }

        internal fun acceptsAddress(address: String?): Boolean =
            address?.trim()?.uppercase(Locale.ROOT) in normalised
    }

    /** Accept only already-bonded devices, so the link is encrypted. */
    data object Bonded : BridgePeerFilter() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun accepts(device: BluetoothDevice, advertisedToken: ByteArray?): Boolean {
            val ok = device.bondState == BluetoothDevice.BOND_BONDED
            if (!ok) Log.w(TAG, "rejecting bridge peer ${device.address}: not bonded")
            return ok
        }
    }

    private companion object {
        const val TAG = "BridgePeerFilter"
    }
}
