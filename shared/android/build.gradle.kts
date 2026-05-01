// Top-level build file for the QuickInk-monorepo Android side.
// Subprojects opt in to the plugins declared in gradle/libs.versions.toml.
//
// `kotlin.android` is no longer listed here because AGP 9's built-in
// Kotlin auto-applies it to every Android module — see the April '26
// new-DSL migration. Plugins that aren't auto-applied (Compose compiler,
// serialization, KSP, GMS Google Services, Crashlytics) stay declared
// here with `apply false` so each module opts in explicitly.

plugins {
    alias(libs.plugins.android.application)        apply false
    alias(libs.plugins.android.library)            apply false
    alias(libs.plugins.compose.compiler)           apply false
    alias(libs.plugins.kotlin.serialization)       apply false
    alias(libs.plugins.ksp)                        apply false
    alias(libs.plugins.google.gms.google.services) apply false
    alias(libs.plugins.google.firebase.crashlytics) apply false
}
