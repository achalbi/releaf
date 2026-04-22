// swift-tools-version:5.9
//
// Releaf iOS — Markdown round-trip spike.
//
// Stand-alone from the main Releaf package to avoid polluting the app's
// dependency graph with swift-markdown until we ship the editor. Delete this
// directory once the production editor lands if the assertions get promoted
// to proper XCTest targets.
//
// Run:
//     swift run --package-path ios/spikes/markdown-roundtrip
//
// See docs/MARKDOWN_EDITOR.md for the round-trip policy this spike verifies.

import PackageDescription

let package = Package(
    name: "MarkdownRoundTrip",
    platforms: [
        .macOS(.v13),
        .iOS(.v16),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-markdown.git", from: "0.3.0"),
    ],
    targets: [
        .executableTarget(
            name: "MarkdownRoundTrip",
            dependencies: [
                .product(name: "Markdown", package: "swift-markdown"),
            ]
        ),
    ]
)
