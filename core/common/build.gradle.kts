// :core:common — Pure Kotlin module
// Contains: Extension functions, unit conversions, MVI base classes (BaseViewModel, etc.)
plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
