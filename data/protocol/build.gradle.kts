// :data:protocol — Pure Kotlin module
// Contains: Per-brand byte-array parsing logic (KingSong, Begode, Inmotion, etc.)
plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
