// :domain — Pure Kotlin module (no android.* dependencies allowed)
// Contains: UseCase interfaces, Repository interfaces, Domain Models (TelemetryState, etc.)
plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
