/*
 * LaunchAnimationView.swift
 *
 * Splash surface that plays the native cinematic launch animation —
 * the SwiftUI port of the React/SVG prototype handed off by design
 * (`design_handoff_quickink_launch/`). Composes:
 *
 *   - `LaunchScene` — Canvas-rendered SVG scene (sky, sun, mountains,
 *     family, growing tree, water stream, etc.).
 *   - `LaunchPointsCounter` / `LaunchLogoLockup` /
 *     `LaunchHomeFeedTransition` — React-style overlays that ride
 *     above the Canvas as native SwiftUI views.
 *
 * Time is driven by `TimelineView(.animation)`, which fires once per
 * display refresh; the scene's per-layer easings derive their `t` from
 * the seconds elapsed since the view appeared. After the prototype's
 * 5.0 s timeline plus a 250 ms tail (matches the JSX preview's
 * fade-to-feed buffer), `onFinished` fires and the host
 * (`QuickInkRoot`) swaps in the auth/main shell.
 *
 * Reduced motion: when `accessibilityReduceMotion` is on we pin
 * `time` at 2.5 s so the user sees a single representative frame
 * (mountains up, family present, tree mid-growth) rather than 5 s of
 * motion — and we shorten `holdSeconds` to ~1.4 s so the dismissal
 * still feels prompt. This addresses the "prefers-reduced-motion
 * fallback" item from `design/SPLASH_INTEGRATION.md`'s deferred list.
 *
 * `target` is the user's lifetime Tree-points balance — fed into the
 * counter pill and the home-feed hero. The host passes the resolved
 * value (default `LaunchAnimationView.defaultTarget` while we settle
 * the sync/async page-count read on the database open path; that's
 * the second item on the deferred list).
 *
 * Counterpart: Android `QuickInkLaunchAnimation.kt`. Both run the
 * native scene at the same 5 s timeline and dismiss to the same
 * post-splash shell.
 */

import SwiftUI

@MainActor
public struct LaunchAnimationView: View {

    /// Lifetime Tree-points balance. Default mirrors the prototype's
    /// preview value until QuickInkRoot wires the live page count.
    public static let defaultTarget: Int = 247

    public let onFinished: () -> Void
    public let target: Int

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Wall-clock start so we can derive elapsed seconds inside the
    /// `TimelineView` body without retaining a per-frame `@State`.
    @State private var startDate: Date = .init()
    @State private var didFinish: Bool = false

    public init(target: Int = LaunchAnimationView.defaultTarget,
                onFinished: @escaping () -> Void) {
        self.target = target
        self.onFinished = onFinished
    }

    public var body: some View {
        TimelineView(.animation) { context in
            let elapsedRaw = context.date.timeIntervalSince(startDate)
            // Reduced motion: pin to a single representative frame
            // (~2.5 s — tree mid-bloom, family + counter visible).
            let t = reduceMotion ? 2.5 : elapsedRaw

            ZStack {
                // Cream behind the Canvas — covers any first-frame
                // gap before the sky gradient ramps in (op = 0 at t=0).
                QuickInkColors.bg.ignoresSafeArea()

                LaunchScene(time: t, palette: LaunchPalettes.dawn)
                    .ignoresSafeArea()

                LaunchPointsCounter(
                    target:  target,
                    time:    t,
                    palette: LaunchPalettes.dawn,
                    show:    true
                )
                LaunchLogoLockup(time: t, palette: LaunchPalettes.dawn)
                    .position(x: UIScreen.mainSafe.width / 2,
                              y: UIScreen.mainSafe.height * 0.20 + 60)
                // (No home-feed transition — the cinematic dismisses
                // straight to the real Home screen, so a baked-in
                // preview of it inside the splash would just play
                // immediately on top of itself.)
            }
            .onChange(of: elapsedRaw) { newValue in
                // 7.5 s total: 5.0 s for the prototype's reveal + 2 s
                // hold on the final state (family, tree, logo and the
                // big top-centred Tree-points counter) so the user
                // has a beat to read the number before the splash
                // dismisses to the real Home screen.
                let limit = reduceMotion ? 1.4 : 7.5
                if !didFinish, newValue >= limit {
                    didFinish = true
                    onFinished()
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("QuickInk launch animation")
        .onAppear { startDate = Date() }
    }
}

// MARK: - Screen-bounds helper

#if canImport(UIKit)
import UIKit

extension UIScreen {
    /// Best-effort active screen size — `UIScreen.main` is deprecated
    /// in iOS 16+ but the replacement (`windowScene.screen`) requires
    /// crawling the active scene set. We do that here so the rest of
    /// the file reads naturally; falls back to `UIScreen.main` if no
    /// connected scene is available (e.g. previews, unit tests).
    static var mainSafe: CGSize {
        if let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first {
            return scene.screen.bounds.size
        }
        return UIScreen.main.bounds.size
    }
}
#endif

#if DEBUG
struct LaunchAnimationView_Previews: PreviewProvider {
    static var previews: some View {
        LaunchAnimationView(onFinished: {})
            .previewDisplayName("Launch animation")
    }
}
#endif
