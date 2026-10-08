/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.data.bridge

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A small on-device ring log for the questions logcat cannot answer after the fact.
 *
 * logcat's `main` buffer holds about half an hour on a busy phone and the glasses clear theirs
 * on every reboot, so "why did the HUD stop at 12:50?" was unanswerable an hour later. This keeps
 * the handful of events that explain a bridge outage (state changes, link ends, pipeline
 * failures, glasses connecting and leaving) in two capped files, so it never grows past
 * `2 x maxFileBytes`.
 *
 * What goes in is the caller's responsibility, and the rule is **no secrets**: never a pairing
 * token, key or credential, and Bluetooth addresses only through [DiagnosticLogs.maskAddress].
 * Everything stays on the device unless the user exports it from Settings.
 *
 * Writing never throws: a full disk or a read-only directory silently drops the line, because
 * a diagnostic aid must not be able to break the bridge it is diagnosing. Identical consecutive
 * lines are collapsed into one line plus a repeat count, so a flapping link cannot flush the
 * useful history out of the ring.
 */
class DiagnosticLog(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {
    private val lock = Any()
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private var lastKey: String? = null
    private var repeats = 0
    private var currentSize = -1L

    fun record(tag: String, message: String) {
        synchronized(lock) {
            val key = "$tag|$message"
            if (key == lastKey) {
                repeats++
                return
            }
            flushRepeats()
            lastKey = key
            append(line(tag, message))
        }
    }

    /** Everything still in the ring, oldest first. Empty when nothing was ever written. */
    fun snapshot(): String = synchronized(lock) {
        flushRepeats()
        runCatching {
            buildString {
                append(previousFile().takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty())
                append(currentFile().takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty())
            }
        }.getOrDefault("")
    }

    fun clear() = synchronized(lock) {
        lastKey = null
        repeats = 0
        currentSize = -1L
        runCatching {
            currentFile().delete()
            previousFile().delete()
        }
        Unit
    }

    private fun flushRepeats() {
        if (repeats > 0) {
            append(line("log", "previous line repeated $repeats more time(s)"))
            repeats = 0
        }
    }

    private fun line(tag: String, message: String): String =
        formatter.format(Instant.ofEpochMilli(clock())) + " [" + clean(tag, MAX_TAG) + "] " +
            clean(message, MAX_MESSAGE) + "\n"

    private fun append(line: String) {
        runCatching {
            dir.mkdirs()
            val bytes = line.toByteArray(Charsets.UTF_8)
            if (currentSize < 0) currentSize = currentFile().takeIf { it.isFile }?.length() ?: 0L
            if (currentSize + bytes.size > maxFileBytes) rotate()
            currentFile().appendBytes(bytes)
            currentSize += bytes.size
        }
    }

    private fun rotate() {
        previousFile().delete()
        currentFile().renameTo(previousFile())
        currentSize = 0L
    }

    private fun currentFile() = File(dir, CURRENT_NAME)
    private fun previousFile() = File(dir, PREVIOUS_NAME)

    private fun clean(text: String, max: Int): String {
        val flat = text.map { if (it.isISOControl()) ' ' else it }.joinToString("")
        return if (flat.length <= max) flat else flat.take(max - 1) + "…"
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES: Long = 128L * 1024
        const val CURRENT_NAME = "diagnostics.log"
        const val PREVIOUS_NAME = "diagnostics.prev.log"
        private const val MAX_TAG = 24
        private const val MAX_MESSAGE = 300
    }
}

/**
 * The process-wide log each app installs at start-up. Until [install] is called (and in plain JVM
 * unit tests) every call is a no-op, so library code can record events without caring whether a
 * sink exists.
 */
object DiagnosticLogs {
    @Volatile private var sink: DiagnosticLog? = null

    fun install(log: DiagnosticLog?) {
        sink = log
    }

    fun record(tag: String, message: String) {
        sink?.record(tag, message)
    }

    fun snapshot(): String = sink?.snapshot().orEmpty()

    fun clear() {
        sink?.clear()
    }

    private val ADDRESS = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    /** `AA:BB:CC:DD:EE:FF` -> `**:**:**:**:EE:FF`: enough to tell two devices apart, not to track one. */
    fun maskAddress(address: String?): String =
        if (address != null && ADDRESS.matches(address)) "**:**:**:**:" + address.takeLast(5).uppercase() else "?"
}
