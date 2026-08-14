// :hud-app — Standalone Android application targeting Rokid AR glasses.
//
// A minimal-surface companion to :app that re-uses the same domain +
// BLE data layer but ships its own Hilt graph, launcher activity and
// HUD-only Compose tree. Runs side-by-side with :app; the two APKs
// have different applicationIds so they can coexist on the same
// device.
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

android {
    namespace = "com.rideflux.hud"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rideflux.hud"
        // Rokid's official glasses-side CXR bridge requires API 28.
        minSdk = 28
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4"
    }

    signingConfigs {
        create("release") {
            // Same credential resolution as :app — environment variables
            // (CI) first, then gitignored local.properties.
            storeFile = signingCredential("KEYSTORE_PATH")?.let { file(it) }
            storePassword = signingCredential("KEYSTORE_PASSWORD")
            keyAlias = signingCredential("KEY_ALIAS")
            keyPassword = signingCredential("KEY_PASSWORD")
        }
    }

    gradle.taskGraph.whenReady {
        val willSignRelease = allTasks.any { task ->
            task.project.path == ":hud-app" &&
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
            // Shrink like :app — without R8 this module packages the
            // entire Compose/Material stack and is ~10x the size of
            // the minified :app APK despite the smaller UI surface.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the shared release keystore so assembleRelease
            // produces an installable APK.
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
    // -- Domain / Data --
    implementation(project(":domain"))
    implementation(project(":data:ble"))
    implementation(project(":data:protocol"))
    implementation(project(":data:bridge"))
    implementation(project(":data:preferences"))

    // Official glasses-side CXR bridge matching client-m 1.0.x.
    implementation(libs.rokid.cxr.service.bridge)

    // -- Compose --
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // icons-extended: ElectricScooter, BluetoothConnected/Disabled,
    // Straighten, Visibility, WarningAmber, ... are extended-only.
    implementation(libs.androidx.material.icons.extended)

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
}
