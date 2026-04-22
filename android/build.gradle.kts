// Top-level build file. Subprojects opt in to the plugins declared in
// gradle/libs.versions.toml. `kotlin.android` is no longer listed here
// because AGP 9's built-in Kotlin auto-applies it to every Android
// module — see the April '26 new-DSL migration for context.

plugins {
    alias(libs.plugins.android.application)  apply false
    alias(libs.plugins.compose.compiler)     apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp)                  apply false
}
