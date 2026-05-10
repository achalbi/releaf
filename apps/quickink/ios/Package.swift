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
        // GRDB — local SQLite. ReleafCoreNotes already pulls this
        // in transitively, but `import GRDB` from a QuickInk
        // source file requires GRDB on this target's own dep list
        // (SwiftPM doesn't re-export). Same version pin as Releaf
        // and ReleafCore — SwiftPM dedupes through resolution.
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "7.0.0"),
        // Lottie iOS — plays the cinematic launch animation handed
        // off by design (`design_handoff_quickink_launch/`). The host
        // view (`LaunchAnimationView`) loads the AE-exported JSON
        // from the bundled `Resources/Animations/quickink_launch.json`
        // and falls through to the minimal-mark `SplashView` when
        // the asset isn't bundled, so the build is safe before/after
        // the JSON lands. `lottie-spm` is the binary-distributed
        // SPM endpoint Airbnb maintains for app-size reasons; the
        // exposed product is named `Lottie` (same as the source
        // package). 4.5.0 is the floor we need for the SwiftUI
        // `LottieView` API used here.
        .package(url: "https://github.com/airbnb/lottie-spm.git", from: "4.5.0"),
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
                // GRDB — used by QuickInkDatabase to open the
                // SQLite file + run migrations.
                .product(name: "GRDB", package: "GRDB.swift"),
                // Lottie — SwiftUI `LottieView` lives here. Used
                // exclusively by `App/LaunchAnimationView.swift` to
                // play the cinematic launch animation; the rest of
                // the app has no Lottie dependency.
                .product(name: "Lottie", package: "lottie-spm"),
            ],
            path: "QuickInk",
            resources: [
                // Bundle Cormorant Garamond + (eventually) Caveat so
                // QuickInkFont.registerAll() can register them at app
                // launch via CTFontManager — see the header note in
                // QuickInkTheme.swift. Until the Xcode app target is
                // created and calls registerAll() in its @main init,
                // SwiftUI text falls back to system serif; the splash
                // screen sidesteps this by using the pre-rendered
                // QuickInkWordmark imageset.
                .process("DesignSystem/Fonts"),
                // Launch-animation Lottie JSON. The file itself
                // (`quickink_launch.json`) is dropped into this
                // directory by the design team — see
                // `design_handoff_quickink_launch/README.md` for
                // the After Effects → Lottie export instructions.
                // Until the JSON lands, the directory holds only
                // a `.gitkeep` marker; SwiftPM's `.process(...)`
                // accepts an empty resource directory and the
                // runtime view (`LaunchAnimationView`) falls
                // through to the minimal-mark `SplashView` when
                // `Bundle.module.url(forResource:withExtension:)`
                // returns nil. The moment the JSON is added, it
                // ships in the bundle and the cinematic plays —
                // no further Package.swift edit needed.
                .process("Resources/Animations"),
            ]
        ),
        // Phase 4 Slice 4.4 — cross-platform interop tests for the
        // shared `notepad_entries` payload. Mirror of Releaf's
        // `ReleafDataTests` target; QuickInk's first test surface,
        // so we add it now alongside the test it hosts.
        // Run via `xcodebuild test -scheme QuickInk` or from the
        // Xcode Test Navigator once the app target hosts it.
        .testTarget(
            name: "QuickInkFeaturesTests",
            dependencies: ["QuickInkFeatures"],
            path: "Tests/QuickInkFeaturesTests"
        ),
    ]
)
