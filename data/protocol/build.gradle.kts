// :data:protocol — Pure Kotlin module
// Contains: Per-brand byte-array parsing logic (KingSong, Begode, Inmotion, etc.)
plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    reports.xml.required.set(true)
}

// :data:protocol exposes :domain types (WheelCodec, DecodeEvent,
// WheelTelemetry, ...) in its public API, so consumers inherit them
// transitively via `api`.
dependencies {
    api(project(":domain"))
    implementation(libs.pev.protocol.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
