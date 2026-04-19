// Root build file — declares plugins without applying them.
// Each submodule applies only the plugins it needs.
plugins {
    alias(libs.plugins.androidApplication)  apply false
    alias(libs.plugins.androidLibrary)      apply false
    alias(libs.plugins.kotlinAndroid)       apply false
    alias(libs.plugins.kotlinJvm)           apply false
    alias(libs.plugins.composeCompiler)     apply false
    alias(libs.plugins.ksp)                 apply false
    alias(libs.plugins.hiltAndroid)         apply false
    alias(libs.plugins.sonarqube)
}

// Pin Java and Kotlin JVM targets across all subprojects so that
// building with a newer JDK (e.g. 25) does not trigger the
// "Inconsistent JVM-target compatibility" check when Kotlin 2.0's
// supported target ceiling is lower than the JDK version.
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = JavaVersion.VERSION_17.toString()
        targetCompatibility = JavaVersion.VERSION_17.toString()
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

sonar {
    properties {
        property("sonar.projectKey", "RideFlux")
        property("sonar.projectName", "RideFlux")

        // SonarCloud when running in CI; local SonarQube otherwise.
        // CI sets SONAR_HOST_URL and SONAR_ORGANIZATION via the workflow env.
        property("sonar.host.url",
            System.getenv("SONAR_HOST_URL") ?: "http://localhost:9000")
        val org = System.getenv("SONAR_ORGANIZATION")
        if (org != null) {
            property("sonar.organization", org)
        }

        // Kotlin source directories (auto-discovered per module, but explicit for clarity)
        property("sonar.sources", "src/main/kotlin,src/main/java")
        property("sonar.tests", "src/test/kotlin,src/test/java")

        // Exclude generated / non-project code from analysis
        property("sonar.exclusions", listOf(
            // Android generated files
            "**/R.java",
            "**/R\$*.java",
            "**/BuildConfig.java",
            "**/Manifest*.java",
            // Dagger / Hilt generated code
            "**/*_Hilt*.java",
            "**/*_HiltModules*.java",
            "**/*_Factory.java",
            "**/*_MembersInjector.java",
            "**/Hilt_*.java",
            "**/*Module_*.java",
            "**/*_Impl*.java",
            // KSP / annotation processor output
            "**/build/generated/**",
            // Data-binding
            "**/databinding/**",
            // Navigation safe-args
            "**/*Directions.java",
            "**/*Args.java",
        ).joinToString(","))

        // Exclude test code from coverage & duplication analysis
        property("sonar.test.exclusions", listOf(
            "**/test/**",
            "**/androidTest/**",
        ).joinToString(","))

        // Coverage: point to JaCoCo XML if available
        property("sonar.coverage.jacoco.xmlReportPaths",
            "**/build/reports/jacoco/**/jacocoTestReport.xml")

        // ── Security hardening ──────────────────────────────────────────
        // Fail the CI pipeline on any Critical or Blocker security issue.
        // These are enforced via the SonarCloud Quality Gate, but listing
        // relevant analysis parameters here for documentation:
        property("sonar.qualitygate.wait", "true")

        // Limit analysis SCM depth to the merge-base for faster PR scans
        property("sonar.scm.provider", "git")
    }
}
