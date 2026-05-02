/*
 * PermissionsScreen.kt
 *
 * Onboarding step 2/3 — camera permission expectation. Doesn't
 * actually request the permission here; the system prompt appears
 * in-context when ML Kit's `GmsDocumentScanning` first launches.
 *
 * Uses `OnboardingScaffold` + `CameraIllustration` from the shared
 * scaffold + illustrations files.
 *
 * Mirror of iOS `PermissionsScreen.swift`.
 */

package app.quickink.mobile.features.onboarding

import androidx.compose.runtime.Composable

@Composable
fun PermissionsScreen(onContinue: () -> Unit) {
    OnboardingScaffold(
        title      = "One tap.\nOne page.",
        subtitle   = "We'll ask for camera access the first time you scan. Change it anytime in Settings.",
        ctaLabel   = "Continue",
        stepIndex  = 1,
        onContinue = onContinue,
    ) {
        CameraIllustration()
    }
}
