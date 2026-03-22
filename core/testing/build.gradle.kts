// :core:testing — Pure Kotlin module
// Contains: BLE mock data, hex-dump test fixtures, coroutine test helpers
plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.test)
    implementation(libs.turbine)
    implementation(libs.junit)
}
