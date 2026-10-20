/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The text file the user saves from Settings: a short header saying which build and phone
 * produced it, then the ring log verbatim. The header has no identifiers beyond the model name.
 */
internal object DiagnosticReport {
    fun build(
        appVersion: String,
        androidRelease: String,
        device: String,
        now: Instant,
        zone: ZoneId,
        log: String,
    ): String = buildString {
        appendLine("RideFlux diagnostic log")
        appendLine("App: $appVersion")
        appendLine("Android: $androidRelease")
        appendLine("Device: $device")
        appendLine("Saved: ${DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone).format(now)}")
        appendLine("Lines below are local time ($zone). No keys, tokens or full Bluetooth addresses.")
        appendLine("----")
        if (log.isBlank()) appendLine("(no events recorded yet)") else append(log)
    }
}
