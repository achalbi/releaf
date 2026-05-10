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
        // Force-portrait wrapper — the SwiftUI library can't reach
        // `UISupportedInterfaceOrientations` (no app target yet) and
        // `UIWindowScene.requestGeometryUpdate` is iOS 16+ + scene-
        // delegate plumbing. Cheapest reliable lock: detect landscape
        // from the GeometryReader's reported size, render the scene
        // into a portrait-shaped frame (h × w when in landscape), then
        // counter-rotate by ±90° so the user sees the cinematic the
        // right way up regardless of how they're holding the phone.
        // The two `.position(...)` blocks centre the rotated content
        // on the actual screen rect.
        GeometryReader { proxy in
            let w = proxy.size.width
            let h = proxy.size.height
            let isLandscape = w > h
            let sceneW = isLandscape ? h : w
            let sceneH = isLandscape ? w : h
            ZStack {
                animationContent
                    .frame(width: sceneW, height: sceneH)
                    .rotationEffect(isLandscape ? .degrees(90) : .degrees(0))
                    .position(x: w / 2, y: h / 2)
            }
            .frame(width: w, height: h)
            .background(QuickInkColors.bg.ignoresSafeArea())
        }
        .ignoresSafeArea()
        .accessibilityElement(children: .combine)
        .accessibilityLabel("QuickInk launch animation")
        .onAppear { startDate = Date() }
    }

    /// The actual splash composition — sky / scene / overlays. Pulled
    /// out of `body` so the force-portrait wrapper can put it inside
    /// a fixed-size frame without touching the per-layer logic.
    @ViewBuilder
    private var animationContent: some View {
        TimelineView(.animation) { context in
            let elapsedRaw = context.date.timeIntervalSince(startDate)
            // Reduced motion: pin to a single representative frame
            // (~2.5 s — tree mid-bloom, family + counter visible).
            let t = reduceMotion ? 2.5 : elapsedRaw

            ZStack {
                LaunchScene(time: t, palette: LaunchPalettes.dawn)
                LaunchPointsCounter(
                    target:  target,
                    time:    t,
                    palette: LaunchPalettes.dawn,
                    show:    true
                )
                // Logo positioned relative to the inner scene frame
                // (which is locked to portrait by the outer wrapper),
                // not to UIScreen.main — so the logo lands in the same
                // place visually whether the device is held portrait
                // or landscape.
                GeometryReader { sceneProxy in
                    LaunchLogoLockup(time: t, palette: LaunchPalettes.dawn)
                        .position(
                            x: sceneProxy.size.width / 2,
                            y: sceneProxy.size.height * 0.20 + 60
                        )
                }
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
    }
}


#if DEBUG
struct LaunchAnimationView_Previews: PreviewProvider {
    static var previews: some View {
        LaunchAnimationView(onFinished: {})
            .previewDisplayName("Launch animation")
    }
}
#endif
