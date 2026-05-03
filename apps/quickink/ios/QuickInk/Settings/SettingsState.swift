/*
 * SettingsState.swift
 *
 * Runtime settings store for QuickInk. Two persisted toggles
 * today; more land as the MVP fills out:
 *
 *   - driveBackupEnabled         User's Drive backup choice
 *                                (set during onboarding screen 3,
 *                                 toggleable via Settings).
 *   - searchablePdfExportEnabled Behind "Experimental" — when on,
 *                                the export sheet (eventual Slice
 *                                6+ surface) offers the searchable-
 *                                PDF path that uses
 *                                `SearchablePdfExporter` in
 *                                `ReleafCoreScan`. v1 default off
 *                                per QUICKINK_PROPOSAL.md §6.3.
 *
 * Storage uses a separate `UserDefaults` keyspace (`quickink.settings.*`)
 * from `OnboardingState`'s `quickink.onboarding.*` so a future
 * onboarding-flow versioning bump doesn't churn user settings.
 *
 * `@MainActor` so SwiftUI bindings are correct without explicit
 * actor hops; all storage reads + writes are cheap.
 */

import Foundation
import SwiftUI

@MainActor
public final class SettingsState: ObservableObject {

    @Published public var driveBackupEnabled: Bool {
        didSet { UserDefaults.standard.set(driveBackupEnabled, forKey: Keys.driveBackup) }
    }

    @Published public var searchablePdfExportEnabled: Bool {
        didSet { UserDefaults.standard.set(searchablePdfExportEnabled, forKey: Keys.searchablePdfExport) }
    }

    /// User-overridden display name shown on the Home greeting. Empty
    /// string means "fall back to the Google account's display name"
    /// — the resolver lives in the Home screen's `resolvedDisplayName`
    /// computed property. Editable from the Settings → Account
    /// section so the user can pick what the app calls them without
    /// rewriting their Google profile.
    @Published public var customDisplayName: String {
        didSet { UserDefaults.standard.set(customDisplayName, forKey: Keys.customDisplayName) }
    }

    /// User's phone number, edited from the Profile screen. Free-form
    /// string (no E.164 normalization yet) since the field is purely
    /// cosmetic / for the user's reference.
    @Published public var phoneNumber: String {
        didSet { UserDefaults.standard.set(phoneNumber, forKey: Keys.phoneNumber) }
    }

    /// `file://` URI of the user's chosen profile photo. Empty when
    /// none has been picked — the avatar then falls back to initial /
    /// person glyph. The picked image is copied into the app's
    /// Documents directory so the URI keeps resolving across launches.
    @Published public var profilePhotoUri: String {
        didSet { UserDefaults.standard.set(profilePhotoUri, forKey: Keys.profilePhotoUri) }
    }

    /// Free-text "personality punchline" — a one-liner the user writes
    /// for themselves. Surfaced on the Profile screen only for now.
    @Published public var personalityPunchline: String {
        didSet { UserDefaults.standard.set(personalityPunchline, forKey: Keys.personalityPunchline) }
    }

    public init() {
        let defaults = UserDefaults.standard
        // Drive backup defaults to true — Drive sync is the value
        // prop, opting out is the explicit choice. Onboarding's
        // sign-in screen writes the user's selection through the
        // `commitOnboardingChoices` static below.
        if defaults.object(forKey: Keys.driveBackup) == nil {
            self.driveBackupEnabled = true
        } else {
            self.driveBackupEnabled = defaults.bool(forKey: Keys.driveBackup)
        }
        // Experimental flag defaults to false. Stays off in App
        // Store builds via a (future) build-time gate even when the
        // user toggles it — that gate isn't wired yet; this is a
        // pure runtime flag for Slice 5.
        self.searchablePdfExportEnabled = defaults.bool(forKey: Keys.searchablePdfExport)
        // Custom display name defaults to "" (empty = use the Google
        // session's name). Read as a plain string so SwiftUI bindings
        // bind cleanly to a TextField.
        self.customDisplayName = defaults.string(forKey: Keys.customDisplayName) ?? ""
        self.phoneNumber          = defaults.string(forKey: Keys.phoneNumber) ?? ""
        self.profilePhotoUri      = defaults.string(forKey: Keys.profilePhotoUri) ?? ""
        self.personalityPunchline = defaults.string(forKey: Keys.personalityPunchline) ?? ""
    }

    /// Called from the onboarding sign-in screen after
    /// `markComplete()` so the user's choice propagates into
    /// Settings. Centralising the write here means the
    /// onboarding state holder doesn't need to know the Settings
    /// keyspace.
    public static func commitOnboardingChoices(driveBackupEnabled: Bool) {
        UserDefaults.standard.set(driveBackupEnabled, forKey: Keys.driveBackup)
    }

    private enum Keys {
        static let driveBackup          = "quickink.settings.drive_backup_enabled"
        static let searchablePdfExport  = "quickink.settings.searchable_pdf_export_enabled"
        static let customDisplayName    = "quickink.settings.custom_display_name"
        static let phoneNumber          = "quickink.settings.phone_number"
        static let profilePhotoUri      = "quickink.settings.profile_photo_uri"
        static let personalityPunchline = "quickink.settings.personality_punchline"
    }
}
