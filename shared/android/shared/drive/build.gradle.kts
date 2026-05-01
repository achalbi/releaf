/*
 * shared/android/shared/drive/build.gradle.kts
 *
 * Build config for the :shared:drive library module — the app-agnostic
 * Google Drive REST client. Mirror of iOS's `ReleafCoreDrive` SwiftPM
 * target.
 *
 * No Releaf-specific dependencies. Both apps consume DriveClient as a
 * thin transport; per-app DriveRepositories layer their entity-shaped
 * facades on top inside their own modules.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace  = "app.releaf.shared.drive"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Coroutines for suspend-based HTTP calls.
    implementation(libs.coroutines.android)

    // OkHttp transport — same client Releaf uses today.
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // kotlinx.serialization — OkHttpDriveClient marshals the Drive REST
    // request/response payloads (DriveFile, list response, create
    // response, etc.) via @Serializable data classes.
    implementation(libs.kotlinx.serialization.json)
}
