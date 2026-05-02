/*
 * SplashView.swift
 *
 * QuickInk's launch / splash surface — minimal-mark variant per the
 * brand prototype board: the QuickInkMark calligraphic Q centered on
 * the cream canvas, no tagline, no chrome.
 *
 * Two ways this surface lives:
 *
 *   1. As the iOS launch screen. Once the Xcode app target lands
 *      (see QuickInkRoot.swift's header note), wire this view into
 *      `Info.plist`'s `UILaunchScreen` dictionary OR a
 *      `LaunchScreen.storyboard` that hosts a `UIHostingController`
 *      around `SplashView`. Until then, this struct compiles + previews
 *      against the library so a regression in mark/canvas tokens is
 *      caught early.
 *
 *   2. As a brief in-process loading view shown while
 *      `QuickInkSyncEnvironment.shared.install(...)` warms the sync
 *      stack and `AuthStore` decides whether to route to
 *      `OnboardingFlow` or the home tab. `QuickInkRoot` can swap to
 *      this view for the first few hundred milliseconds to bridge the
 *      gap between system splash teardown and the first real screen.
 *
 * Sizing: the mark is 36% of the shorter screen edge — same ratio the
 * brand prototype board used for the splash mockup. Centered with the
 * default `Spacer()`-flanked VStack rather than `.frame(...)` so it
 * stays correctly placed across iPhone SE / Pro / Pro Max.
 */

import SwiftUI

@MainActor
public struct SplashView: View {

    public init() {}

    public var body: some View {
        ZStack {
            // Full-bleed cream canvas, edges-to-edges including the
            // status bar and home indicator areas. iOS will animate
            // away from this when the launch screen completes.
            QuickInkColors.bg
                .ignoresSafeArea()

            // The mark. SVG-backed asset preserves its vector
            // representation (see QuickInkMark.imageset/Contents.json),
            // so this scales crisply at any size.
            GeometryReader { proxy in
                let shorter = min(proxy.size.width, proxy.size.height)
                let markSize = shorter * 0.36

                Image("QuickInkMark", bundle: .module)
                    .resizable()
                    .scaledToFit()
                    .frame(width: markSize, height: markSize)
                    .position(x: proxy.size.width / 2,
                              y: proxy.size.height / 2)
                    .accessibilityLabel("QuickInk")
                    .accessibilityAddTraits(.isHeader)
            }
        }
    }
}

#if DEBUG
struct SplashView_Previews: PreviewProvider {
    static var previews: some View {
        Group {
            SplashView()
                .previewDevice("iPhone 15 Pro")
                .previewDisplayName("iPhone 15 Pro — light")

            SplashView()
                .previewDevice("iPhone 15 Pro")
                .preferredColorScheme(.dark)
                .previewDisplayName("iPhone 15 Pro — dark")

            SplashView()
                .previewDevice("iPhone SE (3rd generation)")
                .previewDisplayName("iPhone SE")
        }
    }
}
#endif
