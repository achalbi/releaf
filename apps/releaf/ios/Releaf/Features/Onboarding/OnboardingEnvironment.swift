/*
 * OnboardingEnvironment.swift
 *
 * Environment key for "show the onboarding wizard now". `MainShell`
 * installs the handler; `HomeScreen`'s Quick Guide widget reads it so
 * the presentation state stays co-located with the `.sheet` modifier
 * that owns it.
 */

import SwiftUI

private struct OnboardingShowActionKey: EnvironmentKey {
    static let defaultValue: () -> Void = {}
}

public extension EnvironmentValues {
    /// Invoke to open [OnboardingWizard] from anywhere inside the signed-in
    /// shell. Set by `MainShell`; default is a no-op.
    var showOnboardingWizard: () -> Void {
        get { self[OnboardingShowActionKey.self] }
        set { self[OnboardingShowActionKey.self] = newValue }
    }
}
