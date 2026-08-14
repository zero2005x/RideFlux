// :domain — Pure Kotlin module (no android.* dependencies allowed)
// Contains: UseCase interfaces, Repository interfaces, Domain Models (TelemetryState, etc.)
plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
}

// :domain exposes coroutines types (Flow/StateFlow) in its public API,
// so consumers inherit them transitively via `api`.
dependencies {
    api(libs.kotlinx.coroutines.core)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
