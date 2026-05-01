/*
 * AppMain.swift
 *
 * `@main` entry point for the shipping iOS app target. Lives outside the
 * SwiftPM package because Swift libraries can't host `@main` themselves —
 * the executable target re-declares it and forwards into `ReleafApp`,
 * which carries all of the actual app wiring (sync stack, default-shelf
 * seed, environment objects, root scene).
 *
 * To wire this up in Xcode:
 *   1. File → New → Project → iOS → App, bundle id `app.releaf.mobile`,
 *      interface SwiftUI, language Swift, lifecycle SwiftUI App. Save the
 *      project at `ios/App/Releaf.xcodeproj` and DELETE the auto-generated
 *      `<Name>App.swift` so this file becomes the only `@main`.
 *   2. Drag this file into the new app target.
 *   3. Project → app target → Frameworks, Libraries, and Embedded Content,
 *      then add the local SwiftPM package at `ios/Package.swift` and link
 *      the `ReleafFeatures` library product.
 *   4. Build & run.
 */

import SwiftUI
import ReleafFeatures

@main
struct AppMain: App {
    // `ReleafApp` is itself an `App`, so we just hand its scene back. All
    // of the @StateObjects (AuthStore, UiPreferences) and the sync-stack
    // bootstrap live inside `ReleafApp.init` / `ReleafApp.body` — keeping
    // them there means SwiftUI Previews inside the package can exercise
    // the same root without needing a separate executable target.
    var body: some Scene {
        ReleafApp().body
    }
}
