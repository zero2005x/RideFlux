// :app — Application entry point / MainActivity / NavHost / Hilt setup
import java.util.Base64

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hiltAndroid)
}

// Release-signing credential resolution shared by the signing config below.
// Order: environment variable (CI) → gitignored local.properties (local dev).
fun signingCredential(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: run {
            val f = rootProject.file("local.properties")
            if (!f.exists()) return@run null
            f.readLines().map { it.trim() }
                .firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=')
                ?.takeIf { it.isNotBlank() }
        }

private fun String.asBuildConfigLiteral(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

val rokidClientSecret = signingCredential("ROKID_CLIENT_SECRET")
    ?.replace("-", "")
    .orEmpty()
val rokidSnAuthBase64 = signingCredential("ROKID_SN_AUTH_BASE64")
    // Resolved against the repository root, not the :app module, so that
    // local.properties can carry a stable repo-relative path such as
    // secrets/<id>.lc. Absolute paths still work unchanged.
    ?: signingCredential("ROKID_SN_AUTH_FILE")
        ?.let(rootProject::file)
        ?.takeIf { it.isFile }
        ?.readBytes()
        ?.let { Base64.getEncoder().encodeToString(it) }
        .orEmpty()

android {
    namespace = "com.rideflux.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rideflux.app"
        // Rokid's official consumer CXR client requires API 28.
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "0.1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Optional CXR authentication. Values come only from environment
        // variables or gitignored local.properties; they are never stored
        // in source control. Consumer RV101 builds can connect without
        // them, while provisioned devices may require both values.
        buildConfigField(
            "String",
            "ROKID_CLIENT_SECRET",
            "\"${rokidClientSecret.asBuildConfigLiteral()}\"",
        )
        buildConfigField(
            "String",
            "ROKID_SN_AUTH_BASE64",
            "\"${rokidSnAuthBase64.asBuildConfigLiteral()}\"",
        )
    }

    signingConfigs {
        create("release") {
            // Credentials resolve from environment variables (CI) first,
            // then local.properties (local dev; gitignored). Never commit
            // keystore credentials to the repo. They are optional at
            // configuration time — the task-graph guard below fails the
            // build only when a release artifact will actually be signed
            // without them.
            storeFile = signingCredential("KEYSTORE_PATH")?.let { file(it) }
            storePassword = signingCredential("KEYSTORE_PASSWORD")
            keyAlias = signingCredential("KEY_ALIAS")
            keyPassword = signingCredential("KEY_PASSWORD")
        }
    }

    // Fail fast when a release artifact is actually going to be signed
    // without full credentials. Inspecting the resolved task graph (rather
    // than raw task-name substrings) covers aggregate builds such as
    // `gradle build` / `gradle assemble` and ignores non-signing release
    // tasks (lintRelease, testReleaseUnitTest, ...).
    gradle.taskGraph.whenReady {
        val willSignRelease = allTasks.any { task ->
            task.project.path == ":app" &&
                (task.name == "packageRelease" || task.name == "bundleRelease")
        }
        if (willSignRelease) {
            val cfg = signingConfigs.getByName("release")
            require(cfg.storeFile?.exists() == true) {
                "Release signing requested but KEYSTORE_PATH is missing or invalid. " +
                    "Set it via environment variable or local.properties."
            }
            require(
                !cfg.storePassword.isNullOrEmpty() &&
                    !cfg.keyAlias.isNullOrEmpty() &&
                    !cfg.keyPassword.isNullOrEmpty(),
            ) {
                "Release signing requested but KEYSTORE_PASSWORD / KEY_ALIAS / " +
                    "KEY_PASSWORD are not set."
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // -- Domain --
    implementation(project(":domain"))

    // -- Data --
    implementation(project(":data:ble"))
    implementation(project(":data:protocol"))
    implementation(project(":data:bridge"))
    implementation(project(":data:database"))
    implementation(project(":data:preferences"))
    implementation(project(":core:location"))

    // Official Rokid consumer CXR phone SDK. RV101 already ships the
    // matching com.rokid.cxrservice system component.
    implementation(libs.rokid.cxr.client)

    // -- Compose (BOM-managed) --
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // icons-extended is required: the dashboard/scanner/HUD screens
    // use Cast, CastConnected, Thermostat, BatteryFull, ElectricBolt,
    // BluetoothSearching, etc. — none of which exist in icons-core.
    // R8 release shrinking strips the unused icons, so the APK cost
    // is limited to build time.
    implementation(libs.androidx.material.icons.extended)

    // -- Navigation --
    implementation(libs.androidx.navigation.compose)

    // -- AndroidX --
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // -- Coroutines --
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // -- Hilt --
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // -- Desugar --
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // -- Debug --
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // -- Testing --
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
