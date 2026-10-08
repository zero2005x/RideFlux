/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes the diagnostic report to the document the rider picked. The text is built inside
 * [save], on [io], because reading the ring log is file I/O too.
 */
internal class DiagnosticExporter(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun save(uri: Uri, buildText: () -> String) {
        withContext(io) {
            val bytes = buildText().toByteArray(Charsets.UTF_8)
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Failed to open output stream")
        }
    }
}
