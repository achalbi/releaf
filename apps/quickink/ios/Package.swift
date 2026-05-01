// swift-tools-version:5.9
//
// QuickInk iOS — Swift Package
//
// Sibling app to Releaf, sharing the `ReleafCore` package. This file
// declares one library product (`QuickInkFeatures`) that an Xcode app
// target with bundle ID `app.quickink.mobile` will depend on.
//
// Per QUICKINK_PROPOSAL.md §3, QuickInk picks the six shared products
// it needs explicitly rather than depending on the `ReleafCoreFeatures`
// umbrella — keeps the dep graph honest and makes it visible at PR
// time when QuickInk would otherwise drift into surfaces (notebook
// hierarchy, Releaf-flavoured features) it doesn't actually use.
//
// What QuickInk does NOT pull in (intentional, per §1 "things QuickInk
// does NOT need"):
//   - The `ReleafCoreFeatures` umbrella product (Releaf's full surface)
//   - The Releaf-only files left in `apps/releaf/ios/Releaf/` (DailyPlant,
//     ReleafImpact, CaptureMode, ShelfTheme, the notebook hierarchy, etc.)
//
// The Xcode app target (separate from this SwiftPM package, same pattern
// as Releaf) will own `App/QuickInkApp.swift` (`@main`, `WindowGroup`)
// and depend on this `QuickInkFeatures` library to reach `QuickInkRoot`.

import PackageDescription

let package = Package(
    name: "QuickInk",
    platforms: [
        // iOS-only ships, but mirror Releaf's macOS deployment minimum
        // so the SwiftPM resolver agrees on the floor for transitive
        // deps (GRDB 7 + GoogleSignIn 7 both require macOS 10.15+,
        // pulled in via ReleafCore). See the same comment block in
        // apps/releaf/ios/Package.swift for the full rationale.
        .iOS(.v16),
        .macOS(.v10_15),
    ],
    products: [
        .library(name: "QuickInkFeatures", targets: ["QuickInkFeatures"]),
    ],
    dependencies: [
        // ReleafCore — the shared package both apps depend on. Path
        // walks from `apps/quickink/ios/` up to repo root and into
        // `shared/ios/ReleafCore`. Matches Releaf's path math.
        .package(path: "../../../shared/ios/ReleafCore"),
    ],
    targets: [
        .target(
            name: "QuickInkFeatures",
            dependencies: [
                // The six shared products QuickInk needs at MVP. Note
                // we explicitly skip `ReleafCoreFeatures` (the umbrella)
                // — see file header for why.
                .product(name: "ReleafCoreDesignSystem", package: "ReleafCore"),
                .product(name: "ReleafCoreData",         package: "ReleafCore"),
                .product(name: "ReleafCoreAuth",         package: "ReleafCore"),
                .product(name: "ReleafCoreDrive",        package: "ReleafCore"),
                .product(name: "ReleafCoreSync",         package: "ReleafCore"),
                .product(name: "ReleafCoreNotes",        package: "ReleafCore"),
                .product(name: "ReleafCoreScan",         package: "ReleafCore"),
            ],
            path: "QuickInk"
        ),
    ]
)
