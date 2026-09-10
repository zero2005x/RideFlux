/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.hud.i18n

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Test-only helpers for reading Android string resources straight off
 * disk. Deliberately built on the JDK's own XML parser so the checks add
 * no dependency — the project pins every artifact in
 * `gradle/verification-metadata.xml`, and a new test dependency would
 * mean regenerating it.
 *
 * Duplicated from the `:app` copy rather than shared: the two apps ship
 * independently, and a shared test fixture would need a new Gradle module
 * on the unit-test classpath of both.
 */
internal object StringResources {

    /**
     * Resource qualifiers RideFlux ships translations for. Kept in step
     * with the `:app` list — the two apps are installed as a pair and must
     * not disagree about which languages exist.
     *
     * Two notes on the codes, both of which are easy to get wrong:
     *  - Indonesian is `in`, the legacy ISO 639-1 code. Android does not
     *    resolve `values-id`.
     *  - Mandarin ships as both `zh-rCN` (Simplified) and `zh-rTW`
     *    (Traditional); neither is a fallback for the other.
     */
    val SUPPORTED_LOCALE_QUALIFIERS: List<String> = listOf(
        "ar",      // Modern Standard Arabic
        "de",      // German
        "es",      // Spanish
        "fr",      // French
        "hi",      // Hindi
        "in",      // Indonesian
        "it",      // Italian
        "ja",      // Japanese
        "ko",      // Korean
        "nl",      // Dutch
        "pt",      // Portuguese
        "ru",      // Russian
        "uk",      // Ukrainian
        "ur",      // Urdu
        "vi",      // Vietnamese
        "zh-rCN",  // Mandarin, Simplified
        "zh-rTW",  // Mandarin, Traditional
    )

    /**
     * Locate `<module>/src/main/res`.
     *
     * Gradle runs unit tests with the working directory set to the module
     * directory, but that is a default rather than a guarantee, so probe
     * upward from wherever the JVM actually started: first for the module's
     * own `src/main/res`, then for `<moduleName>/src/main/res` in case the
     * tests were launched from the repository root.
     */
    fun resDir(moduleName: String): File {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            File(dir, "src/main/res").takeIf { File(it, "values/strings.xml").isFile }
                ?.let { return it }
            File(dir, "$moduleName/src/main/res").takeIf { File(it, "values/strings.xml").isFile }
                ?.let { return it }
            dir = dir.parentFile
        }
        error("Could not locate $moduleName/src/main/res from ${File(".").absolutePath}")
    }

    /**
     * Read `<string>` entries, skipping any marked `translatable="false"`.
     * Returns name → raw text.
     */
    fun readStrings(file: File): Map<String, String> {
        require(file.isFile) { "Missing string resource file: $file" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                if (element.getAttribute("translatable") == "false") continue
                put(element.getAttribute("name"), element.textContent)
            }
        }
    }

    /**
     * Positional format specifiers in [value], e.g. `%1$s` → `"1$s"`.
     *
     * A literal `%%` is not a specifier and is skipped: the pattern
     * requires at least one digit and a `$` before the conversion, which
     * `%%` cannot satisfy.
     */
    fun formatSpecifiers(value: String): Set<String> =
        SPECIFIER.findAll(value).map { it.groupValues[1] + "$" + it.groupValues[2] }.toSet()

    private val SPECIFIER = Regex("""%(\d+)\$([a-zA-Z])""")
}
