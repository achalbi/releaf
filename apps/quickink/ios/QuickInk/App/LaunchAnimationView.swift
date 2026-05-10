/*
 * LaunchAnimationView.swift
 *
 * Splash surface that plays the cinematic Lottie launch animation
 * handed off by design (`design_handoff_quickink_launch/README.md`)
 * when the corresponding After Effects → Lottie export is bundled,
 * and falls through to the minimal-mark `SplashView` when it isn't.
 *
 * Asset location: drop the JSON export at
 *   `ios/QuickInk/Resources/Animations/quickink_launch.json`
 * The file is bundled via `Package.swift`'s `.process(...)` entry
 * and looked up at runtime via
 * `Bundle.module.url(forResource:withExtension:)`. A missing file
 * degrades gracefully — the build doesn't depend on it being
 * present, and the user sees the existing minimal-mark splash
 * until design hands the JSON over. The moment the file lands at
 * that path, the cinematic starts playing on next launch.
 *
 * Hosted by `QuickInkRoot` in front of its existing routing — once
 * `onFinished` fires the host swaps to the regular auth/main
 * shell. The safety timeout (README's stated 5.0s + 250ms slack)
 * guarantees we never strand the user on the splash, even if the
 * Lottie composition stalls mid-render or the duration we read off
 * the animation is unreliable.
 *
 * Counterpart: Android `QuickInkLaunchAnimation.kt`. Both load the
 * same JSON file (the README recommends Lottie precisely so one
 * `.json` ships to both platforms unchanged) and both fall back to
 * the minimal-mark splash when the asset is missing.
 *
 * Wiring `target` (the user's lifetime tree-points balance, ticked
 * up by the in-comp counter): TODO. The README documents `target`
 * as one of the animation's three input props. To wire it in
 * SwiftUI Lottie, override the text layer named in the AE comp via
 * `.configure { lav in lav.setValueProvider(... , keypath: ...) }`.
 * Held for a follow-up because (a) the AE layer name isn't fixed
 * yet, and (b) reading the lifetime page count synchronously at
 * splash-time would push the database open before the first
 * SwiftUI frame renders — needs its own design pass on whether to
 * read it sync, async-with-restart, or just let the comp's baked-
 * in default play through.
 */

import SwiftUI
import Lottie

@MainActor
public struct LaunchAnimationView: View {
    public let onFinished: () -> Void

    public init(onFinished: @escaping () -> Void) {
        self.onFinished = onFinished
    }

    public var body: some View {
        // `LottieAnimation.named(_:bundle:)` returns nil when the JSON
        // isn't bundled — that's the empty-resource-directory state
        // the build ships in today. Falling through to `SplashView`
        // keeps the launch path identical to before the Lottie wiring
        // landed, until the AE export is dropped into
        // `Resources/Animations/`.
        if let animation = LottieAnimation.named(
            "quickink_launch",
            bundle: .module
        ) {
            CinematicLaunchPlayer(
                animation:  animation,
                onFinished: onFinished
            )
        } else {
            FallbackLaunchSplash(onFinished: onFinished)
        }
    }
}

// MARK: - Cinematic player (Lottie path)

@MainActor
private struct CinematicLaunchPlayer: View {

    let animation: LottieAnimation
    let onFinished: () -> Void

    /// Latch so `onFinished` only fires once even if both the
    /// duration-based timer and a future `animationDidFinish`
    /// callback race to the finish line.
    @State private var didFinish = false

    var body: some View {
        ZStack {
            // Cream paint behind the animation — the JSON is
            // `cover`-rendered, so the cream shows only as a hairline
            // edge on rare aspect ratios + during the first frame
            // load. Same token the minimal-mark splash uses.
            QuickInkColors.bg
                .ignoresSafeArea()

            // SwiftUI `LottieView` from lottie-ios. Plays once, with
            // the underlying view filling the screen. We don't take
            // the SwiftUI `.animationDidFinish` path because that
            // modifier surface has shifted across Lottie 4.x point
            // releases — the duration-based dismissal below is more
            // reliable across version bumps.
            LottieView(animation: animation)
                .playing(loopMode: .playOnce)
                .resizable()
                .ignoresSafeArea()
        }
        .task {
            // Two-stage dismissal:
            //
            //   1. Sleep for the animation's own duration + 250ms of
            //      slack. This is the happy path — fires the moment
            //      the cinematic ends.
            //
            //   2. Even if step 1 returns earlier than expected (e.g.
            //      the `LottieAnimation.duration` is reported as 0
            //      for a malformed file), the safety ceiling caps
            //      the total hold at the README's quoted 5.0s + 500ms.
            //      Without this, a corrupted JSON could strand the
            //      user on the splash forever.
            let duration = max(0.0, animation.duration)
            let happyPathSeconds: Double = duration + 0.25
            let safetyCeilingSeconds: Double = 5.5
            let waitSeconds = min(
                max(happyPathSeconds, 0.5),
                safetyCeilingSeconds
            )
            try? await Task.sleep(
                nanoseconds: UInt64(waitSeconds * 1_000_000_000)
            )
            finishOnce()
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("QuickInk launch animation")
    }

    private func finishOnce() {
        guard !didFinish else { return }
        didFinish = true
        onFinished()
    }
}

// MARK: - Fallback (asset missing)

/// Minimal-mark splash with a fixed-duration hold — used when the
/// Lottie JSON isn't bundled yet. Reuses the existing `SplashView`
/// for the visual; this wrapper just adds the time-based dismissal
/// (`SplashView` itself is purely declarative and doesn't drive
/// `onFinished`).
@MainActor
private struct FallbackLaunchSplash: View {
    let onFinished: () -> Void

    @State private var didDispatch = false

    /// Same 1.4s hold as the Android `QuickInkSplash` default — the
    /// existing minimal-mark splash duration the user already sees
    /// today. Unchanged so the behaviour is identical until the
    /// cinematic asset lands.
    private let holdSeconds: Double = 1.4

    var body: some View {
        SplashView()
            .task {
                guard !didDispatch else { return }
                didDispatch = true
                try? await Task.sleep(
                    nanoseconds: UInt64(holdSeconds * 1_000_000_000)
                )
                onFinished()
            }
    }
}

#if DEBUG
struct LaunchAnimationView_Previews: PreviewProvider {
    static var previews: some View {
        LaunchAnimationView(onFinished: {})
            .previewDisplayName("Launch animation (falls back to mark)")
    }
}
#endif
