/*
 * shared/android/shared/scan/build.gradle.kts
 *
 * Build config for the :shared:scan library module. Holds the
 * platform-side wrappers around system document-scanning surfaces:
 *
 *   - DocumentScannerLauncher.kt   ML Kit GmsDocumentScanning wrapper.
 *                                  Composable factory that returns a
 *                                  stable launcher handle, with the
 *                                  result-extraction + AttachmentStorage
 *                                  copy already done. Mirror of iOS
 *                                  ReleafCoreScan/DocumentScannerView.swift.
 *
 * Phase-3 follow-ups for this module (NOT in PR #4j):
 *   - OcrEngine protocol + MlKitTextRecognizer impl
 *   - OcrPipeline (multi-page parallel OCR with bounded concurrency)
 *   - SearchablePdfExporter (feature-flagged)
 *
 * Releaf-specific bits intentionally stay in :apps:releaf:
 *   - ScansSection (filter chips, Edit-scan dialog, Toast wording, OCR
 *     fan-out) — that's all UX glue around the launcher.
 *   - ScanCategory (the first-word heuristic + 8 named categories)
 *
 * Mirror of iOS PR #4i (`ReleafCoreScan`); cut line matches that PR's
 * "what stays / what moves" list — the launcher wrapper extracts, the
 * Releaf-flavored UI does not.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace  = "app.releaf.shared.scan"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
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
    // AttachmentStorage.copyIntoStorage — used inside the launcher to
    // turn ML Kit's content:// cache URIs into stable file:// URIs in
    // the app's filesDir before handing them to the caller.
    implementation(project(":shared:data"))

    // Compose. The launcher is a `@Composable` factory using
    // `rememberLauncherForActivityResult`; compose-runtime comes
    // transitively through the BOM + compose-ui. No foundation /
    // material3 deps — this module ships no UI of its own, just the
    // launcher contract.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)

    // ML Kit document scanner — the SDK we wrap.
    implementation(libs.mlkit.document.scanner)
}
