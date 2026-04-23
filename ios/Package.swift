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
                // so both platforms' tests feed from the same file.
                .copy("../../../design-system/fixtures/canonical-json-fixture.json"),
            ]
        ),
    ]
)
