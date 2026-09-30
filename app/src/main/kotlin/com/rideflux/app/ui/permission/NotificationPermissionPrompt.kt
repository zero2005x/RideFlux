/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.permission

/**
 * Runs an action once the notification permission prompt has been answered.
 *
 * The bridge works without notifications: they only surface the running service and the request to
 * approve a pair of glasses, which the app also shows on its own screen. So the prompt must never
 * stand between the rider and what they asked for. [request] runs its action whether the answer was
 * yes, no, or "don't ask again" (which the system reports at once, without showing a dialog).
 *
 * Kept free of Compose and of the activity so the ordering can be unit-tested; the composable that
 * owns the real launcher, and decides whether the platform has anything to ask, is
 * [rememberNotificationPermissionPrompt].
 */
internal class NotificationPermissionPrompt(private val needsPrompt: () -> Boolean) {

    /** Shows the system dialog. Wired to the activity-result launcher once that exists. */
    var launchDialog: () -> Unit = {}

    private var pending: (() -> Unit)? = null

    /** Asks if the permission is still missing, then runs [onDone]; runs it straight away if not. */
    fun request(onDone: () -> Unit) {
        if (!needsPrompt()) {
            onDone()
            return
        }
        // The latest request wins, so an answer that never arrives cannot leave the prompt stuck.
        pending = onDone
        try {
            launchDialog()
        } catch (e: RuntimeException) {
            // A dialog that cannot be shown must not swallow the action it was guarding.
            pending = null
            onDone()
        }
    }

    /** The dialog was answered, whichever way. */
    fun onResult() {
        val action = pending ?: return
        pending = null
        action()
    }
}
