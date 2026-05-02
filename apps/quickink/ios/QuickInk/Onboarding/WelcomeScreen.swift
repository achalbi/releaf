/*
 * WelcomeScreen.swift
 *
 * Onboarding step 1/3 — brand intro. Layered notebook + page hero
 * with an animated coral scan line and detection corners; serif
 * tagline; "Continue" CTA.
 *
 * Uses `OnboardingScaffold` for shell layout and
 * `NotebookScanIllustration` for the hero. All theme tokens come
 * from `QuickInkTheme` (QuickInk-local), not ReleafCore.
 */

import SwiftUI

struct WelcomeScreen: View {
    let onContinue: () -> Void

    var body: some View {
        OnboardingScaffold(
            title:      "A pocket notebook\nthat remembers.",
            subtitle:   "Scan a page, jot a thought, find it later by any word.",
            ctaLabel:   "Continue",
            stepIndex:  0,
            onContinue: onContinue
        ) {
            NotebookScanIllustration()
        }
    }
}
