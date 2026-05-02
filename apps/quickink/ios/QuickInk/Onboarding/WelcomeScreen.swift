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
            title:      "Your notebook,\ndigitised.",
            subtitle:   "Snap any page. Get auto-cropped, OCR'd, categorized, and synced — no fuss.",
            ctaLabel:   "Continue",
            stepIndex:  0,
            onContinue: onContinue
        ) {
            NotebookScanIllustration()
        }
    }
}
