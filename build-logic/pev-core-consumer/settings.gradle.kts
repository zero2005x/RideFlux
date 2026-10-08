pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "PevCoreConsumerBuild"
val consumerRoot = file("../..")
val configuredPath = providers.gradleProperty("pevCoreDir")
    .orElse(providers.environmentVariable("PEV_CORE_DIR"))
    .getOrElse("../M365-Rokid-HUD")
val externalCore = consumerRoot.resolve(configuredPath).resolve("pev-protocol-core")
require(externalCore.resolve("build.gradle.kts").isFile) { "Missing external MIT core at $externalCore" }
include(":pev-protocol-core")
project(":pev-protocol-core").projectDir = externalCore
