/*
 * LocationPermissionScreen.swift
 *
 * Onboarding step 3/4 — asks the user for "When in Use" location
 * permission so the scan + import flows can attach the city / area
 * to each capture's Details card. Tapping Continue calls
 * `LocationService.requestAuthorization()` synchronously through
 * an async task; whichever way the system dialog resolves, the
 * screen advances. A "Skip for now" affordance underneath the CTA
 * lets users move on without granting — they can flip the toggle
 * back on later in Settings, which will trigger the system prompt
 * lazily at first scan.
 *
 * Reuses `OnboardingScaffold` so the visual rhythm + page-indicator
 * dots match the other steps. The illustration is a coral pin glyph
 * built from SF Symbols + accent tones — no bundled asset needed.
 */

import SwiftUI
import CoreLocation

struct LocationPermissionScreen: View {
    let onContinue: () -> Void

    /// Disables the CTA while we're awaiting the system permission
    /// dialog. Prevents a double-tap from queueing two
    /// `requestAuthorization` calls (the second would no-op since
    /// the status would already be non-`.notDetermined`, but the
    /// disabled state reads as more deliberate to the user).
    @State private var isRequesting = false

    var body: some View {
        OnboardingScaffold(
            title:      "Where in the world?",
            subtitle:   "QuickInk attaches the area and city to each scan so you can find them by place later. You can change this anytime in Settings.",
            ctaLabel:   isRequesting ? "Requesting…" : "Allow location",
            stepIndex:  2,
            onContinue: { Task { await requestAndAdvance() } }
        ) {
            LocationIllustration()
        }
        .overlay(alignment: .bottom) {
            // "Skip" escape hatch — sits just below the scaffold's
            // CTA. We don't disable it during the request because a
            // user who taps it mid-prompt has already decided not to
            // wait around for the dialog. Calling `onContinue` here
            // means the flow advances regardless of permission state.
            Button(action: onContinue) {
                Text("Skip for now")
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .padding(.vertical, QuickInkSpacing.s2)
            }
            .buttonStyle(.plain)
            .padding(.bottom, QuickInkSpacing.s5)
        }
        .disabled(isRequesting)
    }

    /// Drive the system permission dialog through `LocationService`,
    /// then move forward regardless of the result. Denied / restricted
    /// states still advance — the user can re-enable from Settings.
    private func requestAndAdvance() async {
        isRequesting = true
        _ = await LocationService.shared.requestAuthorization()
        isRequesting = false
        onContinue()
    }
}

/// Coral location-pin glyph. Sized to match `CameraIllustration`'s
/// visual weight so the two onboarding screens read as a pair. SF
/// Symbol `mappin.and.ellipse` already carries the pin-plus-base
/// metaphor; we wrap it in a soft accent circle for parity with the
/// other illustrations.
private struct LocationIllustration: View {
    var body: some View {
        ZStack {
            Circle()
                .fill(QuickInkColors.accentSoft)
                .frame(width: 220, height: 220)
            Image(systemName: "mappin.and.ellipse")
                .font(.system(size: 96, weight: .regular))
                .foregroundStyle(QuickInkColors.accent)
        }
        .accessibilityHidden(true)
    }
}
