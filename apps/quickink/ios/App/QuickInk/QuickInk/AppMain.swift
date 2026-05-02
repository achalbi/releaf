/*
 * AppMain.swift
 *
 * `@main` entry point for the shipping iOS app target. Lives outside the
 * SwiftPM package because Swift libraries can't host `@main` themselves —
 * the executable target re-declares it and forwards into `QuickInkRoot`,
 * which is the SwiftUI shell defined in the `QuickInkFeatures` library.
 * Same shape as `apps/releaf/ios/App/AppMain.swift`, but `QuickInkRoot`
 * is a `View` (not an `App`), so the `WindowGroup` lives here.
 */

import SwiftUI
import GoogleSignIn
import QuickInkFeatures
import ReleafCoreAuth

@main
struct AppMain: App {

    init() {
        // Bundled fonts (Cormorant Garamond, Caveat) live inside the
        // package resource bundle. Registering at launch via
        // CTFontManager is what lets `QuickInkFont.serif(...)` resolve
        // to the real family instead of falling back to system serif —
        // see the comment block in `QuickInkTheme.swift`.
        QuickInkFont.registerAll()

        // Wire the sync stack + register the BGAppRefreshTask
        // handler once per process. `BGTaskScheduler.register(...)`
        // must run before the app finishes launching, which is why
        // this lives in `@main App.init()` per the design notes in
        // QuickInkSyncEnvironment.swift / SyncScheduler.swift. The
        // bg task identifier `app.quickink.mobile.sync` must be
        // listed in Info.plist's `BGTaskSchedulerPermittedIdentifiers`
        // or this call traps at launch.
        QuickInkSyncEnvironment.shared.install(authStore: makeQuickInkAuthStore())
    }

    var body: some Scene {
        WindowGroup {
            QuickInkRoot()
                // Hand OAuth callbacks back to the Google Sign-In SDK.
                // The callback URL scheme is the reverse-DNS form of
                // the iOS client ID, registered in Info.plist under
                // CFBundleURLTypes → CFBundleURLSchemes. iOS routes
                // any URL with that scheme to this onOpenURL closure;
                // GIDSignIn.handle() resumes the in-flight signIn(...)
                // continuation so the SignInScreen sees `.signedIn`.
                .onOpenURL { url in
                    _ = GIDSignIn.sharedInstance.handle(url)
                }
                // Silent restore on cold launch — if the user signed
                // in on a previous session, GoogleSignIn keeps the
                // refresh token in the keychain and replays it here
                // without showing UI. Falls through cleanly when no
                // prior session exists, leaving AuthStore in
                // `.signedOut` so the onboarding/sign-in screens take
                // over. No-ops in unconfigured builds (no GIDClientID
                // in Info.plist) — matches the same posture
                // GoogleSignInBinding.signInAction takes.
                .task {
                    await GoogleSignInBinding.restorePreviousSignIn(
                        authStore: makeQuickInkAuthStore()
                    )
                }
        }
    }
}
