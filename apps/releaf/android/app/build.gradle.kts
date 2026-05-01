import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9's new DSL auto-applies Kotlin to Android modules
    // (`android.builtInKotlin=true` is now the default) — so we drop
    // the explicit `kotlin.android` alias. Compose-compiler,
    // serialization, and KSP are still explicit because they're
    // separate Kotlin compiler plugins with their own versions.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.google.firebase.crashlytics)
}

// Point Room's KSP processor at a `schemas/` directory under the module.
// Each @Database version gets a JSON dump here on build, which Room uses
// to auto-validate migrations at test time and which we'll diff manually
// when writing new Migration objects. See ReleafDatabase.kt for context.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace  = "app.releaf.mobile"
    // Bumped to 36 because the markdown renderer lib AARs are compiled against
    // API 36. targetSdk intentionally stays at 35 — that gates new *runtime*
    // behavior, and we'd rather opt into that explicitly once we've tested.
    compileSdk = 36

    defaultConfig {
        applicationId = "app.releaf.mobile"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = 1
        versionName   = "0.1.0"

        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose     = true
        buildConfig = true
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

// AGP 9's new DSL pushes the Kotlin compiler knobs out of the `android`
// block and into the top-level `kotlin { }` extension — so the old
// `android { kotlinOptions { jvmTarget = "17" } }` moves here. Also
// flipped from a string literal to the strongly-typed `JvmTarget` enum.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Shared modules (PR #3c). The sync orchestrator + Drive client +
    // supporting types live here. QuickInk depends on the same modules.
    // ReleafSyncDataSource (in this module) implements
    // :shared:sync's SyncDataSource interface.
    implementation(project(":shared:sync"))
    implementation(project(":shared:drive"))
    implementation(project(":shared:data"))  // PR #4b — Uuidv7, IsoClock, FtsQuery, AttachmentStorage
    implementation(project(":shared:auth"))  // PR #4d — AuthStore, GoogleAuthClient, RealGoogleAuthClient
    implementation(project(":shared:notes")) // PR #4f — NotepadEntry, NotepadDao, NotepadCategory

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.firebase.crashlytics)
    debugImplementation(libs.compose.ui.tooling)

    // Cold-launch branded splash. `installSplashScreen()` in MainActivity
    // holds the native splash while Compose's first frame renders, so the
    // branded leaf is visible from the first paint instead of the system
    // white.
    implementation(libs.core.splashscreen)

    // Activity + lifecycle + coroutines
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)

    // JSON
    implementation(libs.kotlinx.serialization.json)

    // Networking (Drive REST — used in a later phase)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Secure local storage for OAuth tokens
    implementation(libs.security.crypto)

    // In-app navigation
    implementation(libs.navigation.compose)

    // Local DB (Room). Schema mirrors design-system/migrations/v1_initial.sql.
    // Room entities cover notepad_entries + notebooks/chapters/pages +
    // sync_state today — other tables are added as features land. KSP
    // processes @Entity/@Dao/@Database annotations.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // Bundled SQLite — supplies FTS5. System SQLite isn't guaranteed to include
    // it (API-35 emulator images omit it), which crashes Room's FTS5 virtual
    // tables at DB-create time. Wired via `.setDriver(BundledSQLiteDriver())`
    // in ReleafDatabase.
    implementation(libs.sqlite.bundled)

    // Background work — powers the Drive sync worker. Uses CoroutineWorker;
    // triggered both on mutations and on a 15-minute periodic cadence.
    implementation(libs.work.runtime.ktx)

    // Markdown rendering for the Notepad editor's preview mode. Notes are
    // stored as canonical CommonMark (see v1_initial.sql notepad_entries.notes).
    implementation(libs.markdown.renderer.android)
    implementation(libs.markdown.renderer.m3)

    // WYSIWYG editor for the notepad and page editors — replaces the custom
    // markdown toolbar. Round-trips to CommonMark so the schema stays happy.
    implementation(libs.rich.editor.compose)

    // Page-editor feature sections. See libs.versions.toml for per-dep notes.
    implementation(libs.coil.compose)
    implementation(libs.play.services.location)
    implementation(libs.mlkit.document.scanner)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.activity.ktx)

    // Voice-note transcription. ML Kit GenAI is the preferred path on
    // API 31+ devices with AICore (uses Gemini Nano on-device, no
    // model bundle). sherpa-onnx (whisper-tiny.en) is the fallback for
    // everything else — see `data/common/SpeechTranscriber.kt` for the
    // dispatch. commons-compress unpacks the `.tar.bz2` model bundle
    // that the sherpa-onnx release ships.
    implementation(libs.mlkit.genai.speech)
    implementation(libs.sherpa.onnx)
    implementation(libs.commons.compress)

    // commons-suncalc — sunrise/sunset/twilight calculator. Drives
    // the precise Rahu Kala window in the Calendar surface.
    implementation(libs.commons.suncalc)

    // Natty — natural-language date/time parser. Handles phrases
    // like "call mom at 7pm tomorrow" / "in 2 hours" / "next
    // monday at 9" far better than our regex fallback can. Used by
    // ReminderParser; the regex path stays as a fallback for the
    // few phrasings Natty misses.
    implementation("com.joestelmach:natty:0.13")

    // Google Sign-In — see libs.versions.toml for per-dep notes.
    // CredentialManager handles the ID-token exchange; AuthorizationClient
    // grants the drive.file scope on top.
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    // Unit tests — JVM, no Android runtime. Scoped so they don't ship.
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.serialization.json)
}
