/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.i18n

import com.rideflux.hud.i18n.StringResources.SUPPORTED_LOCALE_QUALIFIERS
import com.rideflux.hud.i18n.StringResources.formatSpecifiers
import com.rideflux.hud.i18n.StringResources.readStrings
import com.rideflux.hud.i18n.StringResources.resDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the `:hud-app` translation set — see the `:app` twin for the
 * rationale. The extra check here is length: the glasses viewport is
 * 320 dp wide and the placards render at 40 sp, so an over-long
 * translation silently wraps mid-word on the rider's optics.
 */
class LocalizationCoverageTest {

    private val res: File = resDir("hud-app")
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

    /**
     * The centre placards go through `CenteredStatus`, which draws them at
     * 40 sp on a 320 dp-wide viewport — roughly 16 Latin characters per
     * line. Titles are pre-split with `\n`, so each *line* is what has to
     * fit.
     */
    @Test
    fun `placard titles stay within the glasses viewport`() {
        // Asserted first so a rename of any placard key fails loudly here
        // rather than silently dropping that key from the length check.
        assertEquals(
            "Placard keys drifted from the default strings file",
            emptySet<String>(),
            CENTRED_PLACARD_KEYS - defaults.keys,
        )
        assertOverLongLines(CENTRED_PLACARD_KEYS, MAX_PLACARD_LINE_CHARS)
    }

    /**
     * The settings overlay header sits in a row between the CLOSE and EXIT
     * chips at body size, so it gets a looser budget than the placards —
     * but still a budget: it is the widest item on that row.
     */
    @Test
    fun `settings header fits beside the close and exit chips`() {
        assertTrue(SETTINGS_HEADER_KEY in defaults.keys)
        assertOverLongLines(setOf(SETTINGS_HEADER_KEY), MAX_SETTINGS_HEADER_CHARS)
    }

    private fun assertOverLongLines(keys: Set<String>, limit: Int) {
        val problems = mutableListOf<String>()
        for (qualifier in SUPPORTED_LOCALE_QUALIFIERS) {
            val translated = readStrings(File(res, "values-$qualifier/strings.xml"))
            for (key in keys) {
                val value = translated[key] ?: continue
                // "\n" arrives from XML as the two characters backslash + n.
                value.split("\\n").forEach { line ->
                    if (line.length > limit) {
                        problems += "values-$qualifier/$key line \"$line\" is ${line.length} chars"
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    private companion object {
        /**
         * Exactly the titles handed to `CenteredStatus`. `hud_brand_title`
         * is absent because it is `translatable="false"` — the brand mark
         * is identical in every locale.
         */
        val CENTRED_PLACARD_KEYS = setOf(
            "hud_waiting_phone_title",
            "hud_phone_connected_title",
            "hud_scanning_title",
            "hud_connecting_title",
        )

        const val SETTINGS_HEADER_KEY = "hud_settings_title"

        /**
         * Latin scripts are the widest per character at this size; CJK and
         * Arabic translations come in well under the bound, so a single
         * character budget is enough to catch a runaway string.
         */
        const val MAX_PLACARD_LINE_CHARS = 16
        const val MAX_SETTINGS_HEADER_CHARS = 16
    }
}
