/*
 * shared/android/shared/sync/build.gradle.kts
 *
 * Build config for the :shared:sync library module — the app-agnostic
 * Drive sync orchestrator + supporting types. Mirror of iOS's
 * `ReleafCoreSync` SwiftPM target.
 *
 * Depends on:
 *   - :shared:drive  (DriveClient, the orchestrator's Drive transport)
 *   - Room           (SyncStateEntity + SyncStateDao live here so per-app
 *                     databases can register them in their entities list)
 *   - kotlinx.serialization (canonical-JSON wire format)
 *   - kotlinx.coroutines    (suspend orchestration)
 *
 * Does NOT depend on any Releaf-specific package. The seam is the
 * `SyncDataSource` interface — each consuming app implements it.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

ksp {
    // Room schema dump. Per-app databases (ReleafDatabase, eventually
    // QuickInkDatabase) register their own entities and own their own
    // schemas/ dirs; this module's KSP runs only for SyncStateEntity
    // generation but doesn't itself write a database, so the schemas
    // dir here stays empty in practice.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace  = "app.releaf.shared.sync"
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
    implementation(project(":shared:drive"))

    // Coroutines for suspend-based sync flow.
    implementation(libs.coroutines.android)

    // Canonical JSON wire format.
    implementation(libs.kotlinx.serialization.json)

    // Room — SyncStateEntity / Dao definitions only. The bundled-SQLite
    // dep stays with :apps:releaf because the database instance lives
    // there and SQLite driver setup is per-app.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
