// :data:bridge — Phone-as-bridge BLE relay between :app and :hud-app.
//
// Encapsulates a small custom GATT service that the phone (acting as
// BLE peripheral) advertises while it owns the wheel link, and that
// the AR glasses (acting as BLE central) subscribe to in order to
// receive a relayed `WheelTelemetry` snapshot stream. This avoids
// two centrals fighting over the wheel's single-link FFE0 service.
//
// Both :app and :hud-app depend on this module. The protocol /
// codec types are mutual; the BridgeServer is only meaningful on
// the phone (uses BluetoothLeAdvertiser + BluetoothGattServer) and
// the BridgeClient is only meaningful on the glasses (uses BLE
// scan + central GATT). Each side includes only what it needs at
// runtime — both classes are kept here to share the protocol
// constants and binary frame layout in one place.
plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "com.rideflux.data.bridge"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // :data:bridge exposes kotlinx-coroutines types (Flow/CoroutineScope)
    // in its public API (BridgeClient/BridgeServer), inherited transitively.
    api(project(":domain"))
    api(libs.kotlinx.coroutines.core)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
