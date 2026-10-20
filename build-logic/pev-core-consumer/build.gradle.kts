// Bootstrap only the external pure-JVM module's plugins, using this consumer's catalog.
// The module build script and source tree remain authoritative in the external MIT repo.
plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.pev.kover) apply false
}

project(":pev-protocol-core") {
    // Compilation never writes build output into the authoritative external checkout.
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build/pev-protocol-core"))
}
