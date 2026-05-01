/*
 * shared/android/shared/data/build.gradle.kts
 *
 * Build config for the :shared:data library module — pure utility
 * types shared between Releaf and QuickInk.
 *
 * Mirror of iOS's ReleafCoreData target. Holds:
 *   - Uuidv7         (RFC 9562 UUIDv7 generator, byte-identical with iOS)
 *   - IsoClock       (ISO-8601 UTC timestamps with ms precision)
 *   - FtsQuery       (free-form query → FTS5 MATCH expression)
 *   - AttachmentStorage (file:// store under filesDir, app-folder
 *                        parameterized via `appFolderName` for QuickInk)
 *
 * No external deps beyond the Android SDK + AndroidX core-ktx for the
 * URI extension functions AttachmentStorage uses.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace  = "app.releaf.shared.data"
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
    // No external deps. AttachmentStorage's URI helpers (toFile/toUri)
    // were inlined during the PR #4b extract to keep this module
    // dep-free — they're one-liners over the Android SDK's java.io.File
    // and android.net.Uri.
}
