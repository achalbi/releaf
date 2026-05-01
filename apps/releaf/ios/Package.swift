// swift-tools-version:5.9
//
// Releaf iOS — Swift Package
//
// Opens in Xcode (File → Open → Package.swift). Previews render for every
// file under Releaf/DesignSystem/Components and Releaf/Features without
// building a full app target.
//
// To ship an actual iOS app, create an Xcode app target with bundle ID
// `app.releaf.mobile` that depends on the `ReleafFeatures` library below.

import PackageDescription

let package = Package(
    name: "Releaf",
    platforms: [
        // iOS is the only platform this package actually ships to. The
        // Features layer uses UIKit (UITextView via UIViewRepresentable
        // for the rich-text editor), VisionKit (document scanner), CallKit
        // (call attribution), AVAudioSession (voice memo recording), and
        // SwiftUI modifiers like `fullScreenCover` / `.toolbar(_, for:
        // .navigationBar)` that don't exist on macOS. Adding actual macOS
        // support would mean stubbing or redesigning all of those — much
        // bigger than just declaring `.macOS(...)`.
        //
        // BUT: SwiftPM still computes a macOS deployment minimum even
        // for packages that don't really build on macOS, because
        // `swift build` on a Mac defaults to the host triple before
        // anything else gets a say. Without an explicit macOS minimum
        // here, SwiftPM picks 10.13 (the SDK default) — which is below
        // GRDB 7's and GoogleSignIn 7's 10.15 floor, and the resolver
        // refuses with "library X requires macos 10.13 but depends on
        // product Y which requires macos 10.15". So we declare 10.15
        // purely to make the resolver happy; nothing on the macOS path
        // ever runs.
        //
        // SwiftUI Previews continue to work via the iOS Simulator;
        // set Xcode's destination to an iOS Simulator (e.g. "iPhone 16")
        // rather than "My Mac" before building or previewing.
        .iOS(.v16),
        .macOS(.v10_15),
    ],
    products: [
        .library(name: "ReleafDesignSystem", targets: ["ReleafDesignSystem"]),
        .library(name: "ReleafData",         targets: ["ReleafData"]),
        .library(name: "ReleafFeatures",     targets: ["ReleafFeatures"]),
    ],
    dependencies: [
        // Local SQLite wrapper. Mirrors the Android side's Room choice —
        // same v1_initial.sql schema, FTS5 support built in, async/await API.
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "7.0.0"),
        // Google Sign-In SDK. Provides the identity/ID-token flow and
        // the Drive scope grant (via `addScopes`). iOS companion to
        // Android's Credential Manager + AuthorizationClient stack.
        .package(url: "https://github.com/google/GoogleSignIn-iOS.git", from: "7.1.0"),
        // Shared modules — sync orchestrator + Drive client + supporting
        // types live here. QuickInk depends on the same package. PR #3b
        // landed ReleafCoreSync + ReleafCoreDrive; the rest of the
        // ReleafCore targets get filled in over PR #4.
        .package(path: "../../../shared/ios/ReleafCore"),
    ],
    targets: [
        .target(
            name: "ReleafDesignSystem",
            path: "Releaf/DesignSystem"
        ),
        .target(
            name: "ReleafData",
            dependencies: [
                .product(name: "GRDB", package: "GRDB.swift"),
                .product(name: "GoogleSignIn", package: "GoogleSignIn-iOS"),
                // Sync + Drive types now live in ReleafCore. Files in
                // ReleafData that consumed them locally (DriveRepository,
                // LocalDriveRepository, NotebookRepository) gain
                // `import ReleafCoreSync` / `import ReleafCoreDrive`.
                .product(name: "ReleafCoreSync",  package: "ReleafCore"),
                .product(name: "ReleafCoreDrive", package: "ReleafCore"),
            ],
            path: "Releaf/Data",
            resources: [
                // The plant catalogue is the single source of truth at
                // `design-system/plants.json` (90 entries today,
                // append-only). Symlinked into the data target so it
                // ships in the bundle and `DailyPlants` can read it
                // at runtime via `Bundle.module`. SwiftPM rejects
                // resource paths that escape the package dir, so the
                // canonical file is referenced via the symlink at
                // `Notepad/Resources/plants.json` rather than
                // pointing directly at `../../design-system/...`.
                .copy("Notepad/Resources/plants.json"),
            ]
        ),
        .target(
            name: "ReleafFeatures",
            dependencies: [
                "ReleafDesignSystem",
                "ReleafData",
                // ReleafApp + DriveSettingsSection reference SyncEnvironment
                // / SyncStateStore types whose definitions moved into
                // ReleafCoreSync. They re-export back through ReleafData
                // implicitly today (re-export shim is a follow-up nicety),
                // so a direct dep here keeps the compiler happy.
                .product(name: "ReleafCoreSync", package: "ReleafCore"),
            ],
            path: "Releaf/Features"
        ),
        // Unit tests for the Data layer. Runs on iOS simulator via
        // `xcodebuild test -scheme Releaf` or from Xcode's Test
        // Navigator. Host bundle: the SwiftPM test target.
        .testTarget(
            name: "ReleafDataTests",
            dependencies: ["ReleafData"],
            path: "Tests/ReleafDataTests",
            resources: [
                // Shared canonical-JSON fixture lives at repo root
                // (`design-system/fixtures/canonical-json-fixture.json`)
                // so both platforms' tests feed from the same bytes.
                // SwiftPM rejects resource paths that escape the package
                // dir, so we reference it via a symlink at
                // `Resources/canonical-json-fixture.json` that points
                // back at the canonical file. Don't replace the symlink
                // with a real copy — Android reads the same source and
                // keeping a single source of truth prevents drift.
                .copy("Resources/canonical-json-fixture.json"),
            ]
        ),
    ]
)
