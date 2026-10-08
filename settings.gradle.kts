pluginManagement {
    repositories {
        // NOTE: the content filter only admits com.android.*, com.google.*
        // and androidx.* groups from Google's Maven. This matches the
        // current plugin set, but any future plugin marker hosted only on
        // Google's Maven under a non-matching group will fail to resolve —
        // widen the regexes (or document the constraint) rather than
        // debugging a repository-resolution failure.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    // FAIL_ON_PROJECT_REPOS is an intentional hard constraint: all
    // repositories stay centralized here. If a module ever legitimately
    // needs a project-level repository, switch to PREFER_PROJECT instead.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Official Rokid Maven repository. Keep this before the generic
        // repositories so the CXR phone/glasses artifacts resolve from
        // their publisher rather than an unrelated mirror.
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "RideFlux"

// Consume the one authoritative MIT core; never copy its codecs into this GPL tree.
val pevCoreDir = providers.gradleProperty("pevCoreDir")
    .orElse(providers.environmentVariable("PEV_CORE_DIR"))
    .getOrElse("../M365-Rokid-HUD")
val pevCoreSource = file(pevCoreDir)
require(pevCoreSource.resolve("pev-protocol-core/build.gradle.kts").isFile) {
    "PEV core source missing at $pevCoreSource. Set -PpevCoreDir=<HUD repository root> or PEV_CORE_DIR."
}
includeBuild("build-logic/pev-core-consumer") {
    name = "pev-core-source"
    dependencySubstitution {
        substitute(module("io.github.zero2005x.pev:pev-protocol-core"))
            .using(project(":pev-protocol-core"))
    }
}

// -- App --
include(":app")
include(":hud-app")

// -- Domain --
include(":domain")

// -- Data --
include(":data:ble")
include(":data:protocol")
include(":data:bridge")
include(":data:database")
include(":data:preferences")

// -- Core Android services --
include(":core:location")
