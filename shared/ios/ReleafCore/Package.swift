// swift-tools-version:5.9
//
// ReleafCore — shared iOS code for Releaf and QuickInk.
//
// Both apps depend on this package via a local-path dependency:
//
//   .package(path: "../../shared/ios/ReleafCore")
//
// And then link the products they need. Releaf links the umbrella
// `ReleafCoreFeatures`; QuickInk links a smaller subset
// (DesignSystem, Auth, Drive, Sync, Notes, Scan, no Features umbrella).
//
// Targets are organised by responsibility, not by feature. See
// docs/QUICKINK_PROPOSAL.md §3 for the rationale and the full module
// inventory.
//
// Where things are right now (PR #3a — skeleton phase):
//   - Package + 8 targets are declared and resolve cleanly.
//   - ReleafCoreSync ships the `SyncDataSource` protocol + supporting
//     types. No implementation; no consumers; not yet used by Releaf.
//   - The other 7 targets contain only a `Placeholder.swift` so they
//     compile. Their contents arrive in PR #3b (sync extract +
//     refactor) and PR #4 (the rest of the modules).
//
// Why empty placeholders instead of empty target dirs: SwiftPM rejects
// targets with no source files. One trivial Swift file per target is
// the canonical workaround.

import PackageDescription

let package = Package(
    name: "ReleafCore",
    platforms: [
        // Same iOS floor as the Releaf app target. The placeholder
        // contents in this package compile against any iOS, but the
        // real modules that arrive in later PRs use UIKit / VisionKit /
        // PDFKit which all need iOS 16+.
        //
        // macOS is declared at 10.15 for the same SwiftPM resolver
        // reason as Releaf's package — see the comment in
        // apps/releaf/ios/Package.swift. PR #4 adds GRDB and
        // GoogleSignIn-iOS as deps in this package; both require
        // macOS 10.15+, so we set the floor preemptively.
        .iOS(.v16),
        .macOS(.v10_15),
    ],
    products: [
        .library(name: "ReleafCoreDesignSystem", targets: ["ReleafCoreDesignSystem"]),
        .library(name: "ReleafCoreData",         targets: ["ReleafCoreData"]),
        .library(name: "ReleafCoreAuth",         targets: ["ReleafCoreAuth"]),
        .library(name: "ReleafCoreDrive",        targets: ["ReleafCoreDrive"]),
        .library(name: "ReleafCoreSync",         targets: ["ReleafCoreSync"]),
        .library(name: "ReleafCoreNotes",        targets: ["ReleafCoreNotes"]),
        .library(name: "ReleafCoreScan",         targets: ["ReleafCoreScan"]),
        // Umbrella product — re-exports everything above. Releaf depends
        // on this; QuickInk picks the products it needs explicitly.
        .library(name: "ReleafCoreFeatures",     targets: ["ReleafCoreFeatures"]),
    ],
    dependencies: [
        // Google Sign-In SDK. Required by ReleafCoreAuth's
        // RealGoogleAuthClient. Both Releaf and QuickInk consume the
        // same SDK transitively through ReleafCoreAuth — keeps a
        // single OAuth surface across the two apps.
        //
        // 8.0.0 (vs the previous 7.1.0) drags in AppAuth-iOS 1.7.6 —
        // the version that fixes the `OIDExternalUserAgentIOS` main-
        // thread-checker warnings that surface during sign-in. The
        // public surface is unchanged for our use sites.
        .package(url: "https://github.com/google/GoogleSignIn-iOS.git", from: "8.0.0"),
        // GRDB — local SQLite wrapper. Used by ReleafCoreNotes'
        // NotepadRepository (and any future SQLite-backed shared
        // repos). Same version pin as Releaf's Package.swift; SwiftPM
        // dedupes through the resolution graph.
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "7.0.0"),
    ],
    targets: [
        // ─── Foundation layer ───────────────────────────────────────
        .target(
            name: "ReleafCoreData",
            path: "Sources/ReleafCoreData"
        ),

        // ─── Design system ──────────────────────────────────────────
        .target(
            name: "ReleafCoreDesignSystem",
            path: "Sources/ReleafCoreDesignSystem"
        ),

        // ─── Auth / Drive / Sync ────────────────────────────────────
        .target(
            name: "ReleafCoreAuth",
            dependencies: [
                "ReleafCoreData",
                .product(name: "GoogleSignIn", package: "GoogleSignIn-iOS"),
            ],
            path: "Sources/ReleafCoreAuth"
        ),
        .target(
            name: "ReleafCoreDrive",
            dependencies: ["ReleafCoreData"],
            path: "Sources/ReleafCoreDrive"
        ),
        .target(
            // Sync depends on Data (for shared types like UUIDv7,
            // IsoClock once those move) and on Drive (for the
            // DriveClient protocol it calls). It does NOT depend on
            // Auth — auth tokens are passed in per-call.
            name: "ReleafCoreSync",
            dependencies: ["ReleafCoreData", "ReleafCoreDrive"],
            path: "Sources/ReleafCoreSync"
        ),

        // ─── Feature-shaped modules ─────────────────────────────────
        .target(
            name: "ReleafCoreNotes",
            dependencies: [
                "ReleafCoreData",
                "ReleafCoreSync",
                // PR #4g — editor views (RichTextFormatBar,
                // EditorModeToggle, EntryDateRow, NotesEditorSheet)
                // moved into this target use AppColors / AppText etc.
                "ReleafCoreDesignSystem",
                .product(name: "GRDB", package: "GRDB.swift"),
            ],
            path: "Sources/ReleafCoreNotes"
        ),
        .target(
            name: "ReleafCoreScan",
            dependencies: ["ReleafCoreData"],
            path: "Sources/ReleafCoreScan"
        ),

        // ─── Umbrella ───────────────────────────────────────────────
        .target(
            name: "ReleafCoreFeatures",
            dependencies: [
                "ReleafCoreDesignSystem",
                "ReleafCoreData",
                "ReleafCoreAuth",
                "ReleafCoreDrive",
                "ReleafCoreSync",
                "ReleafCoreNotes",
                "ReleafCoreScan",
            ],
            path: "Sources/ReleafCoreFeatures"
        ),
    ]
)
