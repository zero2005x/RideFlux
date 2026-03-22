pluginManagement {
    repositories {
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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RideFlux"

// -- App --
include(":app")

// -- Core --
include(":core:ui")
include(":core:common")
include(":core:testing")

// -- Domain --
include(":domain")

// -- Data --
include(":data:ble")
include(":data:protocol")
include(":data:database")
include(":data:preferences")

// -- Feature --
include(":feature:dashboard")
include(":feature:trips")
include(":feature:settings")
include(":feature:device-scan")
include(":feature:hud-gateway")

// -- Rokid Glasses App --
include(":hud-app")
