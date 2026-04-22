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
            ],
            path: "Releaf/Data"
        ),
        .target(
            name: "ReleafFeatures",
            dependencies: ["ReleafDesignSystem", "ReleafData"],
            path: "Releaf/Features"
        ),
    ]
)
