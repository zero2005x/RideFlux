// :data:protocol — Pure Kotlin module
// Contains: Per-brand byte-array parsing logic (KingSong, Begode, Inmotion, etc.)
plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
}

// :data:protocol exposes :domain types (WheelCodec, DecodeEvent,
// WheelTelemetry, ...) in its public API, so consumers inherit them
// transitively via `api`.
dependencies {
    api(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
