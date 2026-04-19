// :app — Application entry point / MainActivity / NavHost / Hilt setup
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hiltAndroid)
}

android {
    namespace = "com.wheellog.next"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wheellog.next"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Populated from environment variables in CI or local.properties
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
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
    }
}

dependencies {
    // -- Feature modules --
    // Legacy feature modules (com.wheellog.next.*) are quarantined
    // until they are ported to the new com.rideflux.* domain surface.
    // Re-enable each dependency as the module is migrated.
    // implementation(project(":feature:dashboard"))
    // implementation(project(":feature:trips"))
    // implementation(project(":feature:settings"))
    // implementation(project(":feature:device-scan"))
    // implementation(project(":feature:hud-gateway"))

    // -- Domain & Core --
    implementation(project(":domain"))
    implementation(project(":core:ui"))
    implementation(project(":core:common"))

    // -- Data --
    implementation(project(":data:ble"))
    implementation(project(":data:protocol"))
    // implementation(project(":data:database"))   // legacy, quarantined
    // implementation(project(":data:preferences")) // legacy, quarantined

    // -- Compose (BOM-managed) --
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
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
