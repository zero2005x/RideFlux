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
    jacoco
}

// The Sonar / sonar-scanner plugin transitively pulls in an older
// BouncyCastle (bcprov-jdk15on) that shadows the modern one AGP's
// `validateSigningDebug` needs. Without this force we see:
//   NoClassDefFoundError: org/bouncycastle/asn1/edec/EdECObjectIdentifiers
// which was only added in BC 1.71+. Forcing bcprov-jdk18on on the
// buildscript classpath resolves it minimally, without changing the
// Sonar plugin version.
//
// NOTE: the eachDependency rule below rewrites *every*
// `org.bouncycastle:*jdk15on` coordinate on the buildscript classpath
// to `-jdk18on:1.78.1` (plus `force`). Only `-jdk15on`-suffixed
// artifacts are rewritten — `bcprov-jdk15to18` / `bcpkix-jdk15to18`
// are left untouched and could still mix versions. This project's
// current buildscript plugins (AGP/KSP/Hilt/Sonar) have been verified
// compatible with BC 1.78.1; if a plugin is added that depends on the
// original `-jdk15on` artifacts, revisit this rewrite.
buildscript {
    dependencies {
        classpath("org.bouncycastle:bcprov-jdk18on:1.78.1")
        classpath("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    }
    configurations.classpath {
        resolutionStrategy {
            force(
                "org.bouncycastle:bcprov-jdk18on:1.78.1",
                "org.bouncycastle:bcpkix-jdk18on:1.78.1",
            )
            eachDependency {
                if (requested.group == "org.bouncycastle" &&
                    requested.name.endsWith("-jdk15on")
                ) {
                    useTarget(
                        "org.bouncycastle:${requested.name.replace("-jdk15on", "-jdk18on")}:1.78.1",
                    )
                    because("AGP signing needs BC >= 1.71 (EdECObjectIdentifiers)")
                }
            }
        }
    }
}

// Pin Java and Kotlin JVM targets across all subprojects so that
// building with a newer JDK (e.g. 25) does not trigger the
// "Inconsistent JVM-target compatibility" check when Kotlin 2.0's
// supported target ceiling is lower than the JDK version.
subprojects {
    apply(plugin = "jacoco")

    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = JavaVersion.VERSION_17.toString()
        targetCompatibility = JavaVersion.VERSION_17.toString()
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // Make every module's JVM unit-test task emit JaCoCo coverage data
    // (build/jacoco/<task>.exec). The aggregate report task below
    // collects each module's exec and produces a single XML for Sonar.
    tasks.withType<Test>().configureEach {
        configure<JacocoTaskExtension> {
            isIncludeNoLocationClasses = true
            // jdk.internal.* is loaded by the JVM and cannot be instrumented.
            excludes = listOf("jdk.internal.*")
        }
    }
}

// ── Aggregate JaCoCo report ───────────────────────────────────────────
// SonarCloud's coverage gate requires a single XML at a known location.
// `./gradlew jacocoTestReport` runs every module's unit tests and merges
// the resulting *.exec files into one report.
tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Aggregate JaCoCo coverage report across all subprojects."

    // Lazy task collections so dependencies resolve regardless of when
    // this task is realized (findByName returns null for not-yet-
    // configured subprojects and would silently drop them).
    dependsOn(
        subprojects.map { sub ->
            sub.tasks.matching { it.name == "testDebugUnitTest" || it.name == "test" }
        }
    )

    // Gradle 9 strictly validates implicit task dependencies. The
    // per-module `jacocoTestReport` tasks (added by `apply(plugin = "jacoco")`
    // in the subprojects block above) write into the same
    // `build/jacoco/*.exec` paths this aggregate consumes via fileTree,
    // so declare the relationship explicitly to satisfy task validation.
    dependsOn(
        subprojects.map { sub ->
            sub.tasks.matching { it.name == "jacocoTestReport" }
        }
    )

    // Gradle 9 validates task ordering more strictly. When this task is run
    // in the same invocation as `assembleDebug`, Android signing validation
    // tasks (e.g. :hud-app:validateSigningDebug) produce files visible to
    // this aggregate report task. Declare ordering without forcing signing
    // validation to run when only coverage is requested.
    mustRunAfter(
        subprojects.map { sub ->
            sub.tasks.matching {
                it.name == "validateSigningDebug" ||
                    it.name == "validateSigningRelease" ||
                    it.name == "packageDebug" ||
                    it.name == "packageRelease"
            }
        }
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    val coverageExcludes = listOf(
        "**/R.class",
        "**/R\$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "**/Hilt_*.*",
        "**/*_Hilt*.*",
        "**/*_Factory.*",
        "**/*_MembersInjector.*",
        "**/*Module_*.*",
        "**/*_Impl*.*",
    )

    classDirectories.setFrom(
        files(
            subprojects.map { sub ->
                fileTree("${sub.layout.buildDirectory.get().asFile}/tmp/kotlin-classes/debug") {
                    exclude(coverageExcludes)
                }
            },
            subprojects.map { sub ->
                fileTree("${sub.layout.buildDirectory.get().asFile}/classes/kotlin/main") {
                    exclude(coverageExcludes)
                }
            },
            subprojects.map { sub ->
                fileTree("${sub.layout.buildDirectory.get().asFile}/classes/java/main") {
                    exclude(coverageExcludes)
                }
            },
            // Android modules with Java sources compile them here (AGP 8.x);
            // without this they appear uncovered even when their tests pass.
            subprojects.map { sub ->
                fileTree("${sub.layout.buildDirectory.get().asFile}/intermediates/javac/debug/classes") {
                    exclude(coverageExcludes)
                }
            },
        )
    )

    sourceDirectories.setFrom(
        files(
            subprojects.map { "${it.projectDir}/src/main/kotlin" },
            subprojects.map { "${it.projectDir}/src/main/java" },
        )
    )

    // Wire exec data to the specific test-task outputs that were actually
    // run (avoiding stale leftovers and duplicated AGP-merged exec files
    // from previous builds).
    val testExecFiles = subprojects.flatMap { sub ->
        listOf(
            "${sub.layout.buildDirectory.get().asFile}/jacoco/testDebugUnitTest.exec",
            "${sub.layout.buildDirectory.get().asFile}/jacoco/test.exec",
            "${sub.layout.buildDirectory.get().asFile}/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
        )
    }
    executionData.setFrom(files(testExecFiles).filter { it.exists() })
}

sonar {
    properties {
        property("sonar.projectKey", "zero2005x_RideFlux")
        property("sonar.projectName", "RideFlux")
        property("sonar.organization", "zero2005x")
        property("sonar.host.url", "https://sonarcloud.io")

        // Kotlin source directories are auto-discovered per module; the
        // root project contains no source code, so sonar.sources /
        // sonar.tests are intentionally not set here.

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

        // Exclude generated / non-project code from coverage analysis.
        // NOTE: sonar.test.exclusions removes files from analysis scope
        // entirely (not just coverage), and the previous broad `**/test/**`
        // / `**/androidTest/**` patterns matched every declared test file,
        // effectively disabling test-code analysis. Sonar auto-detects
        // test source sets per module, so no test exclusion is set here.


        // Coverage: aggregate JaCoCo XML produced by the root
        // :jacocoTestReport task (defined above). Use an absolute path so
        // every subproject resolves the same root report instead of looking
        // under its own build directory.
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory
                .file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml")
                .get()
                .asFile
                .absolutePath,
        )

        // ── Security hardening ──────────────────────────────────────────
        // Direct scans wait for the Quality Gate by default. The GitHub
        // Actions workflow temporarily overrides this while the clean-room
        // rewrite's historical coverage baseline is remediated.
        property("sonar.qualitygate.wait", "true")

        // Limit analysis SCM depth to the merge-base for faster PR scans
        property("sonar.scm.provider", "git")
    }
}
