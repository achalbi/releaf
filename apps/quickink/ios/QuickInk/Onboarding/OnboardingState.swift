/*
 * OnboardingState.swift
 *
 * State machine for QuickInk's 3-screen onboarding (welcome /
 * permissions / sign-in). Steps advance forward only; there's no
 * "back" affordance in this v1 because the screens are
 * informational + a single-tap sign-in. If/when a multi-step
 * permissions screen lands, add a `previous()` and the matching UI
 * affordance.
 *
 * Completion persistence uses `UserDefaults` keyed under
 * `quickink.onboarding.completed.v1`. The `.v1` suffix is
 * deliberate — if QuickInk ever ships a v2 onboarding (added screen,
 * different content), bump to `.v2` so existing users see the new
 * flow once.
 */

import Foundation
import SwiftUI

@MainActor
public final class OnboardingState: ObservableObject {

    public enum Step: Sendable {
        case welcome, permissions, signIn
    }

    @Published public private(set) var step: Step = .welcome

    /// Drive backup preference toggled on the sign-in screen. Held
    /// locally for now; the eventual Settings screen (Slice 5)
    /// persists this through `UiPreferences` or QuickInk's own
    /// OnboardingPreferences.
    @Published public var driveBackupEnabled: Bool = true

    public init() {}

    public func advance() {
        step = switch step {
        case .welcome:     .permissions
        case .permissions: .signIn
        case .signIn:      .signIn  // terminal — caller invokes onComplete instead
        }
    }

    public func markComplete() {
        UserDefaults.standard.set(true, forKey: Self.completedKey)
    }

    public static func isCompleted() -> Bool {
        UserDefaults.standard.bool(forKey: completedKey)
    }

    private static let completedKey = "quickink.onboarding.completed.v1"
}
