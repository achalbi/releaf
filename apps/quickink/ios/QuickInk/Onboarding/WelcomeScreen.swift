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
        // Title is split into upright + italic-coral parts to
        // reproduce the mock's "Your notebook,\n*digitised.*"
        // editorial flourish; subtitle copy is lifted verbatim from
        // the mock so what ships matches the brief.
        OnboardingScaffold(
            title:       "Your notebook,",
            titleAccent: "digitised.",
            subtitle:    "Snap any page from your Quickink notebook — we'll crop, clean, and save it for you.",
            ctaLabel:    "Continue",
            stepIndex:   0,
            totalSteps:  4,
            onContinue:  onContinue
        ) {
            NotebookScanIllustration()
        }
    }
}
