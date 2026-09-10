/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.i18n

import com.rideflux.app.i18n.StringResources.SUPPORTED_LOCALE_QUALIFIERS
import com.rideflux.app.i18n.StringResources.formatSpecifiers
import com.rideflux.app.i18n.StringResources.readStrings
import com.rideflux.app.i18n.StringResources.resDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the `:app` translation set.
 *
 * The default `values/strings.xml` is the source of truth. Every
 * `values-<qualifier>/strings.xml` must define exactly the same set of
 * translatable keys, with matching format specifiers — a mismatch is
 * either a missing translation or a crash waiting to happen
 * (`IllegalFormatException` when the argument list does not line up).
 *
 * Strings marked `translatable="false"` (brand names, SI units, pure
 * punctuation) are excluded: they are deliberately not duplicated per
 * locale.
 */
class LocalizationCoverageTest {

    private val res: File = resDir("app")
    private val defaults = readStrings(File(res, "values/strings.xml"))

    @Test
    fun `default strings file is non-empty and has no duplicate keys`() {
        assertTrue("values/strings.xml defines no translatable strings", defaults.isNotEmpty())
        val raw = File(res, "values/strings.xml").readText()
        val declared = Regex("""<string\s+name="([^"]+)"""").findAll(raw).map { it.groupValues[1] }.toList()
        val duplicates = declared.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals("Duplicate string names in values/strings.xml", emptySet<String>(), duplicates)
    }

    @Test
    fun `every supported locale has a strings file`() {
        val missing = SUPPORTED_LOCALE_QUALIFIERS.filterNot {
            File(res, "values-$it/strings.xml").isFile
        }
        assertEquals("Locales without a strings.xml", emptyList<String>(), missing)
    }

    @Test
    fun `every locale defines exactly the translatable keys of the default`() {
        val problems = mutableListOf<String>()
        for (qualifier in SUPPORTED_LOCALE_QUALIFIERS) {
            val translated = readStrings(File(res, "values-$qualifier/strings.xml"))
            val missing = defaults.keys - translated.keys
            val extra = translated.keys - defaults.keys
            if (missing.isNotEmpty()) problems += "values-$qualifier missing: ${missing.sorted()}"
            if (extra.isNotEmpty()) problems += "values-$qualifier unknown: ${extra.sorted()}"
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `no locale has a blank translation`() {
        val blanks = mutableListOf<String>()
        for (qualifier in SUPPORTED_LOCALE_QUALIFIERS) {
            readStrings(File(res, "values-$qualifier/strings.xml"))
                .filterValues { it.isBlank() }
                .keys
                .forEach { blanks += "values-$qualifier/$it" }
        }
        assertEquals(emptyList<String>(), blanks)
    }

    @Test
    fun `format specifiers match the default in every locale`() {
        val problems = mutableListOf<String>()
        for (qualifier in SUPPORTED_LOCALE_QUALIFIERS) {
            val translated = readStrings(File(res, "values-$qualifier/strings.xml"))
            for ((key, defaultValue) in defaults) {
                val translatedValue = translated[key] ?: continue
                val expected = formatSpecifiers(defaultValue)
                val actual = formatSpecifiers(translatedValue)
                if (expected != actual) {
                    problems += "values-$qualifier/$key expected $expected but was $actual"
                }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }
}
