/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.bridge

import android.content.ContentResolver
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticExporterTest {
    private val uri = mockk<Uri>()

    @Test
    fun theBuiltTextIsWrittenAsUtf8ToTheChosenDocument() = runBlocking {
        val sink = ByteArrayOutputStream()
        val resolver = mockk<ContentResolver> { every { openOutputStream(uri) } returns sink }

        DiagnosticExporter(resolver, Dispatchers.Unconfined).save(uri) { "診斷 ok\n" }

        assertEquals("診斷 ok\n", sink.toString(Charsets.UTF_8))
    }

    @Test
    fun aDocumentThatCannotBeOpenedIsReportedNotSwallowed() = runBlocking {
        val resolver = mockk<ContentResolver> { every { openOutputStream(uri) } returns null }

        val failure = runCatching {
            DiagnosticExporter(resolver, Dispatchers.Unconfined).save(uri) { "x" }
        }.exceptionOrNull()

        assertEquals("Failed to open output stream", failure?.message)
    }

    @Test
    fun aFailureWhileBuildingTheTextPropagates() = runBlocking {
        val resolver = mockk<ContentResolver>(relaxed = true)

        val failure = runCatching {
            DiagnosticExporter(resolver, Dispatchers.Unconfined).save(uri) { error("boom") }
        }.exceptionOrNull()

        assertEquals("boom", failure?.message)
    }
}
