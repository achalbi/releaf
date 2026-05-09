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
import java.util.Properties

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

// Release signing — keystore secrets live OUTSIDE the repo. Two
// supported sources (in order):
//   1. `apps/quickink/android/keystore.properties` — local file
//      that overrides nothing in git. CI can materialize this from
//      a secret. Format:
//        storeFile=../upload-keystore.jks
//        storePassword=...
//        keyAlias=upload
//        keyPassword=...
//   2. Environment variables (CI fallback):
//        QUICKINK_UPLOAD_STORE_FILE, QUICKINK_UPLOAD_STORE_PASSWORD,
//        QUICKINK_UPLOAD_KEY_ALIAS,  QUICKINK_UPLOAD_KEY_PASSWORD
// When neither source resolves, `release` falls through to debug
// signing so local debug-on-release builds still link (Play
// uploads will still need a real key — see SIGNING.md).
val keystorePropsFile = rootProject.file("../../apps/quickink/android/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use(::load)
}
fun keystoreValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

val uploadStoreFile     = keystoreValue("storeFile",     "QUICKINK_UPLOAD_STORE_FILE")
val uploadStorePassword = keystoreValue("storePassword", "QUICKINK_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias      = keystoreValue("keyAlias",      "QUICKINK_UPLOAD_KEY_ALIAS")
val uploadKeyPassword   = keystoreValue("keyPassword",   "QUICKINK_UPLOAD_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    uploadStoreFile, uploadStorePassword, uploadKeyAlias, uploadKeyPassword,
).all { !it.isNullOrBlank() }

// versionCode lives in a sidecar file so the `bumpVersionCode` task
// can rewrite it without touching this script. Falls back to 1 if the
// file is missing (e.g. fresh checkout before first release).
val versionPropsFile = rootProject.file("../../apps/quickink/android/version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) versionPropsFile.inputStream().use(::load)
}
val computedVersionCode: Int = (versionProps.getProperty("versionCode") ?: "1").toInt()

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
        versionCode   = computedVersionCode
        versionName   = "0.1.2"
    }

    buildFeatures {
        compose = true
        // BuildConfig fields below need this — without it, AGP 9
        // prunes the generated `BuildConfig` class entirely (the
        // default flipped to false in 8.x) and references to
        // `BuildConfig.ANALYTICS_ENABLED` etc. fail to compile.
        buildConfig = true
    }

    defaultConfig {
        // ── Analytics outbox flags ─────────────────────────────────
        // The QuickInk analytics backend lives at
        // api-quickink.thoughtbasics.com. Two BuildConfig fields:
        //
        //   ANALYTICS_BASE_URL — root URL for the JSON API. The
        //     AnalyticsApiClient appends `/v1/identify` and
        //     `/v1/events/capture/batch` for the two POST paths.
        //
        //   ANALYTICS_ENABLED  — kill switch. When false, the
        //     AnalyticsFlushWorker's scheduleAll() / requestImmediate()
        //     short-circuit, and the worker's doWork() returns
        //     success() immediately. Outbox enqueue still happens —
        //     we just don't ship the rows. Flip to true once a build
        //     is verified end-to-end against the backend.
        //
        // For solo-dev we hard-code the URL here. In a real CI
        // pipeline this would come from a secrets file / env var.
        buildConfigField("String",  "ANALYTICS_BASE_URL", "\"https://api-quickink.thoughtbasics.com\"")
        buildConfigField("boolean", "ANALYTICS_ENABLED",  "true")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file("../../${uploadStoreFile!!}")
                storePassword = uploadStorePassword
                keyAlias      = uploadKeyAlias
                keyPassword   = uploadKeyPassword
                // Play App Signing rotates the app signing key on
                // Google's side; we ship V1+V2 from the upload key
                // and Play re-signs with the deployment key.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8 / resource shrinking — drops dead code + unused
            // resources, obfuscates symbols. Required for an AAB
            // that's reasonable in size on Play.
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Wire the upload key when present; otherwise fall
            // through to debug signing so `assembleRelease` still
            // produces an installable APK locally for smoke
            // testing. Play uploads will fail unless real signing
            // is configured.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
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

    // Compose downloadable Google Fonts. Drives QuickInk's editorial
    // type stack (Roboto Serif for the New-York-style serif, Inter
    // for the SF-Pro-style sans). Version is BOM-aligned — no
    // explicit version literal needed because `compose.bom` above
    // pins it. Cert hashes for the Play Services font provider live
    // in `res/values/font_certs.xml` and are referenced via
    // `R.array.com_google_android_gms_fonts_certs` from
    // `QuickInkTypography.kt`.
    implementation("androidx.compose.ui:ui-text-google-fonts")

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

    // Modern Android 12+ SplashScreen API + compat backport for
    // older Android versions. Drives the system splash window
    // (background + animated icon) so the icon shown during cold
    // launch matches our brand. Activated in `MainActivity.onCreate`
    // via `installSplashScreen()`; theme attributes live in
    // `res/values/themes.xml` under `Theme.QuickInk.Splash`.
    //
    // TODO: move the version literal into libs.versions.toml under
    // a `core-splashscreen = "1.0.1"` entry + a corresponding
    // `androidx-core-splashscreen` library binding once the catalog
    // is open (sibling :apps:releaf likely already has it pinned;
    // reuse that version key).
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Haze — Compose backdrop-blur. Drives the frosted-glass effect
    // on the home BottomNavBar (`hazeChild` on the bar, `haze` source
    // on the scrolling content behind it). `haze-materials` ships the
    // iOS-Material-style HazeStyle presets (`HazeMaterials.regular`
    // etc.) we pull from. Falls back to a tint on API < 32 where
    // RenderEffect isn't available.
    implementation(libs.haze)
    implementation(libs.haze.materials)

    // Coil — image loading for scan-preview thumbnails on the home
    // rail and the full-bleed preview in `ScanDetailScreen`. Reads
    // the `file://` URIs that `captures.preview_uri` stores.
    implementation(libs.coil.compose)

    // Phase 4 Slice 4.4 — unit-test toolchain. Keeps the test
    // dep set minimal (junit + kotlinx-serialization for the
    // canonical-JSON interop test). Match the version pins
    // Releaf uses so both apps' test outputs stay aligned.
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.serialization.json)
}

// Increments versionCode in apps/quickink/android/version.properties.
// Run before each Play upload:
//   ./gradlew :apps:quickink:bumpVersionCode :apps:quickink:bundleRelease
//
// The action body deliberately captures only `propsFile` (a plain
// `java.io.File`) and re-reads the properties at execution time.
// Referencing the script-level `versionProps` / `versionPropsFile`
// from inside `doLast` pulls the Project / Script object into the
// task's serialized state, which Gradle's configuration cache
// rejects with "cannot serialize Gradle script object references".
// Re-reading also keeps the bump idempotent if the file is touched
// between configuration and execution.
tasks.register("bumpVersionCode") {
    group = "release"
    description = "Increment versionCode in version.properties."
    val propsFile = versionPropsFile
    doLast {
        val props = Properties().apply {
            if (propsFile.exists()) propsFile.inputStream().use(::load)
        }
        val current = (props.getProperty("versionCode") ?: "1").toInt()
        val next = current + 1
        val header = propsFile.takeIf { it.exists() }
            ?.readLines()
            ?.takeWhile { it.isBlank() || it.startsWith("#") }
            ?.joinToString("\n")
            ?.let { if (it.isBlank()) "" else "$it\n" }
            ?: ""
        propsFile.writeText("${header}versionCode=$next\n")
        println("versionCode: $current → $next")
    }
}
