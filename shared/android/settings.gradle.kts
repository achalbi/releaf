/*
 * shared/android/settings.gradle.kts
 *
 * Root Gradle build for the QuickInk-monorepo Android side. Hosts both
 * apps (Releaf, eventually QuickInk) and the shared library modules
 * they depend on. See docs/QUICKINK_PROPOSAL.md §3 for the layout.
 *
 * Module layout:
 *   :apps:releaf                  — apps/releaf/android/app/   (Releaf production app)
 *   :apps:quickink                — apps/quickink/android/app/ (Phase 3, not yet present)
 *   :shared:sync                  — shared/android/shared/sync/
 *   :shared:drive                 — shared/android/shared/drive/
 *   (other :shared:* modules arrive in PR #4)
 *
 * `projectDir` overrides let us point `:apps:releaf` at a sibling tree
 * outside this build root. Gradle handles that fine — it's the standard
 * approach for monorepos where module paths don't mirror the on-disk
 * directory layout.
 */

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx ships Android AARs only via GitHub releases; JitPack
        // mirrors them under `com.github.k2-fsa:sherpa-onnx:<tag>`. Used by
        // :apps:releaf for on-device speech transcription.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ReleafMonorepo"

// ─── Apps ──────────────────────────────────────────────────────────────
//
// `include(":apps:releaf")` makes Gradle ALSO load `:apps` as an
// implicit parent project, defaulting its projectDir to
// `shared/android/apps/` — which doesn't exist. Re-point it at the
// real apps/ at the repo root so the parent resolves to a real (no-
// build-file but existing) directory. Gradle won't try to apply any
// plugin to `:apps` itself because there's no `apps/build.gradle.kts`.
//
// `:shared` doesn't need this trick because `shared/android/shared/`
// exists naturally (it contains sync/ and drive/).

include(":apps:releaf")
project(":apps").projectDir         = file("../../apps")
project(":apps:releaf").projectDir  = file("../../apps/releaf/android/app")

// ─── Shared library modules ────────────────────────────────────────────

include(":shared:sync")
project(":shared:sync").projectDir = file("shared/sync")

include(":shared:drive")
project(":shared:drive").projectDir = file("shared/drive")

include(":shared:data")
project(":shared:data").projectDir = file("shared/data")
