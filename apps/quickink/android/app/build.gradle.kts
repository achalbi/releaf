/*
 * apps/quickink/android/app/build.gradle.kts
 *
 * QuickInk Android — `:apps:quickink` module config. Sibling app to
 * `:apps:releaf`, sharing the `:shared:*` library modules per
 * QUICKINK_PROPOSAL.md §3.
 *
 * Phase-3 scaffold scope: the app builds, launches, and renders an
 * empty Compose root. Things deliberately deferred until the MVP flow
 * lands (per the scope handoff in the conversation thread):
 *   - Splash assets / app icon (separate brand pass — §4.7)
 *   - QuickInkDatabase + Room migration (forked v1_initial.sql per §3)
 *   - Onboarding / Camera / Scan / Notes screens (§6.4)
 *   - Firebase / GMS plugins (out of band, gated on the brand pass)
 *
 * Why this isn't a copy of `:apps:releaf`'s build file: Releaf's deps
 * pull in voice (sherpa-onnx + commons-compress + mlkit-genai-speech),
 * reminders (natty), markdown rendering (m3 markdown lib), Firebase
 * Crashlytics, the Room schema compiler, GoogleSignIn, etc. — none of
 * which QuickInk's MVP needs. The scaffold takes the minimum set
 * (Compose + activity-compose + lifecycle + coroutines + the seven
 * `:shared:*` modules) so the build is fast and the dep graph stays
 * honest.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 auto-applies kotlin.android. Compose-compiler stays
    // explicit because it's a separate Kotlin compiler plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    // Room schema + DAO codegen.
    alias(libs.plugins.ksp)
    // Phase 3 — encode OcrResult.blocks into the
    // ocr_results.blocks_json column via @Serializable.
    alias(libs.plugins.kotlin.serialization)
}

// Room schema dump dir — Room writes one JSON per @Database
// version, used for migration validation in tests. Keep alongside
// Releaf's convention so future migration work follows the same
// shape across both apps.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace  = "app.quickink.mobile"
    // Same SDK floors as Releaf — markdown lib AARs need API 36 at
    // compile time; targetSdk holds at 35 until we explicitly opt in
    // to runtime-behavior changes.
    compileSdk = 36

    defaultConfig {
        applicationId = "app.quickink.mobile"  // §8 lock per proposal
        minSdk        = 26
        targetSdk     = 35
        versionCode   = 1
        versionName   = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Shared modules — explicit list per QUICKINK_PROPOSAL.md §3.
    // Mirrors the six iOS products QuickInk picks (DesignSystem,
    // Data, Auth, Drive, Sync, Notes, Scan); no umbrella module on
    // the Android side, so each :shared:* is named individually.
    implementation(project(":shared:designsystem"))
    implementation(project(":shared:data"))
    implementation(project(":shared:auth"))
    implementation(project(":shared:drive"))
    implementation(project(":shared:sync"))
    implementation(project(":shared:notes"))
    implementation(project(":shared:scan"))

    // Compose. Same surface Releaf uses; runtime + ui-graphics come
    // transitively through the BOM. material-icons-extended is the
    // glyph library the onboarding + main screens reach into for
    // DocumentScanner / CameraAlt / CloudUpload icons.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Activity + lifecycle + coroutines — minimum for a Compose-only
    // entry point.
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)

    // Compose Navigation — Slice 6 replaces QuickInkRoot's hand-
    // rolled state-machine routing with a real NavHost. Same
    // version Releaf uses; Gradle dedupes through the catalog.
    implementation(libs.navigation.compose)

    // Phase 4 Slice 4.1 — Google Sign-In stack. Same four deps
    // Releaf uses, lifted verbatim:
    //   - androidx.credentials + credentials-play-services-auth:
    //     modern ID-token-based sign-in via Credential Manager.
    //   - googleid: `GetGoogleIdOption` builder.
    //   - play-services-auth: `AuthorizationClient.authorize()`
    //     for the Drive scope grant on top.
    // Real client is wired via `QuickInkAuthBinding`'s
    // `rememberQuickInkSignInAction` composable hook; stub
    // fallback when `R.string.google_web_client_id` is still the
    // placeholder.
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    // JSON encoding for ocr_results.blocks_json.
    implementation(libs.kotlinx.serialization.json)

    // Local DB (Room). Schema mirrors
    // `shared/design-system/migrations/quickink/v1_initial.sql`.
    // QuickInkDatabase registers entities from :shared:notes
    // (NotepadEntry) and :shared:sync (SyncStateEntity) plus
    // QuickInk-specific CaptureEntity + OcrResultEntity defined in
    // this app target. KSP processes the @Entity / @Dao / @Database
    // annotations; the bundled SQLite driver supplies FTS5 (system
    // SQLite isn't guaranteed to include it).
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlite.bundled)

    // Phase 4 Slice 4.2b — WorkManager hosts QuickInkSyncWorker
    // (15-minute periodic + on-mutation one-shots). Same artifact
    // Releaf's :apps:releaf module uses; the version is centralized
    // in libs.versions.toml. Worker classes live in this app
    // module — :shared:sync stays free of WorkManager so the
    // orchestrator remains plain coroutines.
    implementation(libs.work.runtime.ktx)

    // Phase 4 Slice 4.4 — unit-test toolchain. Keeps the test
    // dep set minimal (junit + kotlinx-serialization for the
    // canonical-JSON interop test). Match the version pins
    // Releaf uses so both apps' test outputs stay aligned.
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.serialization.json)
}
