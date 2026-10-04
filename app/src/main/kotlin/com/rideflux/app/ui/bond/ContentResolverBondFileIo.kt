/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.bond

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [BondFileIo] over the system document picker. The files hold only encrypted backups. */
class ContentResolverBondFileIo(private val resolver: ContentResolver) : BondFileIo {
    override suspend fun read(location: String, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            resolver.openInputStream(Uri.parse(location))?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return@use null
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun write(location: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.openOutputStream(Uri.parse(location), "wt")?.use { it.write(bytes); it.flush(); true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private companion object { const val BUFFER_BYTES = 8 * 1024 }
}
