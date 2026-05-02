/*
 * WelcomeScreen.kt
 *
 * Onboarding step 1/3 — brand intro. Layered notebook + page hero
 * with an animated coral scan line and detection corners; serif
 * tagline; "Continue" CTA.
 *
 * Uses `OnboardingScaffold` for shell layout and
 * `NotebookScanIllustration` for the hero. All theme tokens come
 * from `QuickInkTheme` (QuickInk-local), not :shared:designsystem.
 *
 * Mirror of iOS `WelcomeScreen.swift`.
 */

package app.quickink.mobile.features.onboarding

import androidx.compose.runtime.Composable

@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    OnboardingScaffold(
        title      = "A pocket notebook\nthat remembers.",
        subtitle   = "Scan a page, jot a thought, find it later by any word.",
        ctaLabel   = "Continue",
        stepIndex  = 0,
        onContinue = onContinue,
    ) {
        NotebookScanIllustration()
    }
}
