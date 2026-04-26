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
        // iOS-only. The Features layer uses UIKit (UITextView via
        // UIViewRepresentable for the rich-text editor), VisionKit
        // (document scanner), CallKit (call attribution),
        // AVAudioSession (voice memo recording), and SwiftUI
        // modifiers like `fullScreenCover` / `.toolbar(_, for:
        // .navigationBar)` that don't exist on macOS. Adding macOS
        // support here would mean stubbing or redesigning all of
        // those — much bigger than just declaring `.macOS(...)`.
        //
        // SwiftUI Previews still work on a Mac: they run in an iOS
        // Simulator, so set Xcode's destination to an iOS Simulator
        // (e.g. "iPhone 16") rather than "My Mac" before building or
        // previewing.
        .iOS(.v16),
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
            ],
            path: "Releaf/Data"
        ),
        .target(
            name: "ReleafFeatures",
            dependencies: ["ReleafDesignSystem", "ReleafData"],
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
