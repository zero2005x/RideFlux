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
 */
sealed class BridgePeerFilter {

    /** True if [device] may be connected to. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    abstract fun accepts(device: BluetoothDevice): Boolean

    /**
     * Accept the first advertiser of the bridge service UUID.
     *
     * Insecure: retained for setups that have no way to learn the
     * phone's MAC ahead of time. Every acceptance is logged at WARN so
     * the exposure is visible in a bug report.
     */
    data object AcceptAny : BridgePeerFilter() {
        override fun accepts(device: BluetoothDevice): Boolean {
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
        override fun accepts(device: BluetoothDevice): Boolean {
            Log.w(TAG, "rejecting bridge peer ${device.address}: no paired phone")
            return false
        }
    }

    /** Accept only devices whose MAC is in [addresses] (case-insensitive). */
    data class Allowlist(private val addresses: Set<String>) : BridgePeerFilter() {
        private val normalised: Set<String> =
            addresses.mapTo(mutableSetOf()) { it.trim().uppercase(Locale.ROOT) }

        init {
            require(normalised.isNotEmpty()) { "allowlist must not be empty" }
        }

        override fun accepts(device: BluetoothDevice): Boolean {
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
        override fun accepts(device: BluetoothDevice): Boolean {
            val ok = device.bondState == BluetoothDevice.BOND_BONDED
            if (!ok) Log.w(TAG, "rejecting bridge peer ${device.address}: not bonded")
            return ok
        }
    }

    private companion object {
        const val TAG = "BridgePeerFilter"
    }
}
