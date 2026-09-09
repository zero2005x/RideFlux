/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import android.os.SystemClock

/**
 * Process-wide guard against Android's undocumented BLE scan throttle.
 *
 * `BluetoothLeScanner` silently stops delivering results when an app
 * calls `startScan` more than five times in a rolling 30-second window.
 * There is no callback for it — `onScanFailed` is never invoked, the
 * scan simply goes quiet — so a retry loop that keeps restarting scans
 * starves itself and looks exactly like "the phone isn't advertising".
 *
 * Every `startScan` in this module goes through [reserve] first. The
 * budget is deliberately one below the platform limit: the glasses run
 * [BridgeClient]'s reconnect loop and [BridgePeerScanner]'s pairing scan
 * from the same process, and leaving a slot spare means opening the
 * pairing screen can never push the reconnect loop over the edge.
 *
 * @param maxStarts scans permitted per window.
 * @param windowMillis rolling window length.
 * @param now monotonic clock; injectable so the policy is unit-testable.
 */
class BleScanThrottle(
    private val maxStarts: Int = MAX_STARTS_PER_WINDOW,
    private val windowMillis: Long = WINDOW_MILLIS,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {

    /** Reservation times (not call times) of the scans in the current window. */
    private val reservations = ArrayDeque<Long>()

    /**
     * Claim a scan slot and return how long the caller must wait before
     * actually calling `startScan`.
     *
     * The slot is booked at the returned instant rather than at call
     * time, so concurrent callers queue behind each other instead of all
     * being told to wait the same amount and then firing together.
     */
    @Synchronized
    fun reserve(): Long {
        val currentTime = now()
        while (reservations.isNotEmpty() && currentTime - reservations.first() >= windowMillis) {
            reservations.removeFirst()
        }
        val waitMillis = if (reservations.size < maxStarts) {
            0L
        } else {
            (reservations.first() + windowMillis - currentTime).coerceAtLeast(0L)
        }
        if (reservations.size >= maxStarts) reservations.removeFirst()
        reservations.addLast(currentTime + waitMillis)
        return waitMillis
    }

    /** Forget the recorded history. Test seam; not used in production code. */
    @Synchronized
    internal fun reset() {
        reservations.clear()
    }

    companion object {
        /** Android allows 5 per 30 s; keep one in reserve. */
        const val MAX_STARTS_PER_WINDOW: Int = 4

        const val WINDOW_MILLIS: Long = 30_000L

        /** The single instance every scan in this process shares. */
        val shared: BleScanThrottle = BleScanThrottle()
    }
}
