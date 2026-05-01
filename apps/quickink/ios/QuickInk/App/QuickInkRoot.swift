/*
 * QuickInkRoot.swift
 *
 * QuickInk's top-level SwiftUI view. The eventual Xcode app target
 * (with bundle ID `app.quickink.mobile`) will host this in a
 * `WindowGroup` from its `@main App` struct — same arrangement Releaf
 * uses for its app shell vs. the Features library.
 *
 * Phase 3 scaffold: this is the placeholder root that proves the
 * package compiles, links against `ReleafCore`, and renders a
 * brand-token-aware screen. The MVP navigation graph
 * (Onboarding → Camera-first Home → Scan + OCR → Notes list → Editor)
 * lands incrementally on top of this surface — see QUICKINK_PROPOSAL.md
 * §6.4 for the screen list.
 *
 * The placeholder body intentionally exercises shared design tokens
 * (`AppColors.canvas`, `AppText.pageTitle`, `AppSpacing.s4`) so a
 * regression in the `ReleafCoreDesignSystem` ↔ QuickInk wiring shows
 * up at first build rather than at MVP-feature time.
 */

import SwiftUI
import ReleafCoreDesignSystem

public struct QuickInkRoot: View {

    public init() {}

    public var body: some View {
        ZStack {
            AppColors.canvas
                .ignoresSafeArea()

            VStack(alignment: .leading, spacing: AppSpacing.s4) {
                Text("QuickInk")
                    .font(AppText.pageTitle)
                    .foregroundStyle(AppColors.textPrimary)

                Text("Phase 3 scaffold")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }
            .padding(AppSpacing.s5)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }
}

#if DEBUG
struct QuickInkRoot_Previews: PreviewProvider {
    static var previews: some View {
        QuickInkRoot()
    }
}
#endif
