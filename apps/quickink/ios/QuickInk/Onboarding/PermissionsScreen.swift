/*
 * PermissionsScreen.swift
 *
 * Onboarding step 2/3. Educates the user about the camera
 * permission QuickInk will request the first time they tap "scan."
 *
 * Doesn't actually request the permission here — that lands at
 * scan time (the system permission sheet appears in-context when
 * VisionKit's `VNDocumentCameraViewController` first presents).
 * Pre-empting it on this screen would just produce an awkward
 * out-of-context prompt; the screen's job is to set expectation.
 *
 * Uses `OnboardingScaffold` + `CameraIllustration` from the shared
 * scaffold file.
 */

import SwiftUI

struct PermissionsScreen: View {
    let onContinue: () -> Void

    var body: some View {
        OnboardingScaffold(
            title:      "One tap.\nOne page.",
            subtitle:   "We'll ask for camera access the first time you scan. Change it anytime in Settings.",
            ctaLabel:   "Continue",
            stepIndex:  1,
            onContinue: onContinue
        ) {
            CameraIllustration()
        }
    }
}
