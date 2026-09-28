/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.version

import com.rideflux.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Guards against version divergence between the phone app (`:app`) and the
 * glasses HUD (`:hud-app`).
 *
 * The phone app is distributed via Google Play while the glasses HUD is
 * distributed via GitHub Releases. A protocol or layout mismatch between the
 * two ends leaves the rider with an unrecoverable empty display or failing
 * telemetry. This test ensures both modules always declare identical
 * `versionCode` and `versionName`.
 */
class ModuleVersionAlignmentTest {

    @Test
    fun appAndHudVersionCodesAndNamesMatch() {
        val appBuildFile = findBuildGradle("app")
        val hudBuildFile = findBuildGradle("hud-app")

        val appVersionCode = extractInt(appBuildFile, "versionCode")
        val hudVersionCode = extractInt(hudBuildFile, "versionCode")

        val appVersionName = extractString(appBuildFile, "versionName")
        val hudVersionName = extractString(hudBuildFile, "versionName")

        assertNotNull("app/build.gradle.kts missing versionCode", appVersionCode)
        assertNotNull("hud-app/build.gradle.kts missing versionCode", hudVersionCode)
        assertNotNull("app/build.gradle.kts missing versionName", appVersionName)
        assertNotNull("hud-app/build.gradle.kts missing versionName", hudVersionName)

        assertEquals("Module versionCode mismatch between :app and :hud-app", appVersionCode, hudVersionCode)
        assertEquals("Module versionName mismatch between :app and :hud-app", appVersionName, hudVersionName)

        assertEquals("BuildConfig.VERSION_CODE must match build.gradle.kts", appVersionCode, BuildConfig.VERSION_CODE)
        assertEquals("BuildConfig.VERSION_NAME must match build.gradle.kts", appVersionName, BuildConfig.VERSION_NAME)
    }

    private fun extractInt(file: File, key: String): Int? {
        val pattern = Regex("""\b$key\s*=\s*(\d+)""")
        return pattern.find(file.readText())?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractString(file: File, key: String): String? {
        val pattern = Regex("""\b$key\s*=\s*"([^"]+)"""")
        return pattern.find(file.readText())?.groupValues?.get(1)
    }

    private fun findBuildGradle(moduleName: String): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            val fileInSub = File(dir, "$moduleName/build.gradle.kts")
            if (fileInSub.isFile) return fileInSub
            if (dir.name == moduleName) {
                val direct = File(dir, "build.gradle.kts")
                if (direct.isFile) return direct
            }
            dir = dir.parentFile
        }
        error("Could not locate $moduleName/build.gradle.kts starting from ${File(".").canonicalPath}")
    }
}
