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

    /// When true, the scan + import flows fetch the device's current
    /// location (with reverse-geocoded city / area) and attach it to
    /// the capture row. When false, the scan flow skips the fetch
    /// entirely — captures save with NULL location columns. Defaults
    /// to true so users who grant permission during onboarding see
    /// the feature working immediately; the Settings → Sync row lets
    /// them turn it off without revoking system permission.
    @Published public var locationForScansEnabled: Bool {
        didSet { UserDefaults.standard.set(locationForScansEnabled, forKey: Keys.locationForScans) }
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

    /// MRU list of recent search queries — surfaced as pills under
    /// the Search screen's input. Newest-first, capped at
    /// `maxRecentSearches`. Mutated through `pushRecentSearch` so
    /// dedupe + cap stay centralised. Stored as a `[String]` array
    /// in UserDefaults (small enough to avoid the JSON overhead).
    @Published public private(set) var recentSearches: [String] {
        didSet { UserDefaults.standard.set(recentSearches, forKey: Keys.recentSearches) }
    }

    /// Push `query` to the front of the recent-searches list, drop
    /// any prior occurrence (case-insensitive), cap at
    /// `maxRecentSearches`. Empty / whitespace-only queries are
    /// ignored — typing a character then deleting it shouldn't
    /// pollute the pill row.
    public func pushRecentSearch(_ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        var next = recentSearches.filter { $0.caseInsensitiveCompare(trimmed) != .orderedSame }
        next.insert(trimmed, at: 0)
        if next.count > Self.maxRecentSearches {
            next = Array(next.prefix(Self.maxRecentSearches))
        }
        recentSearches = next
    }

    /// Wipe the recent-searches list. Bound to a "Clear" affordance.
    public func clearRecentSearches() {
        recentSearches = []
    }

    private static let maxRecentSearches = 10

    /// User's picked primary color (Coral / Leaf Green / Leaf Yellow /
    /// Leaf Dry). The QuickInkRoot view reads this every render and
    /// applies an `.accentColor()` overlay tinted to the picked
    /// family's resolved variant (light → deep, dark → base).
    /// Stored as the enum's raw value so the keyspace stays stable.
    @Published public var primaryColor: PrimaryColor {
        didSet { UserDefaults.standard.set(primaryColor.rawValue, forKey: Keys.primaryColor) }
    }

    /// User's theme override. `system` (default) follows the OS
    /// setting; `light` / `dark` force the corresponding mode by
    /// passing a non-nil ColorScheme to the root content.
    @Published public var themeMode: ThemeMode {
        didSet { UserDefaults.standard.set(themeMode.rawValue, forKey: Keys.themeMode) }
    }

    /// Last capture surface the user picked on QuickCaptureScreen
    /// — Document or BusinessCard. Persisted across sessions so
    /// the pill toggle remembers their choice. First-launch
    /// fallback is `.document`; landing card-first would surprise
    /// users who came for document scanning. Storage key matches
    /// Android: `quickink.capture.last_mode`.
    @Published public var lastCaptureMode: CaptureMode {
        didSet { UserDefaults.standard.set(lastCaptureMode.analyticsKey, forKey: Keys.lastCaptureMode) }
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
        // Location-for-scans defaults to true so the onboarding step
        // can prompt for system permission and have the toggle
        // already "on" the first time the user opens Settings.
        if defaults.object(forKey: Keys.locationForScans) == nil {
            self.locationForScansEnabled = true
        } else {
            self.locationForScansEnabled = defaults.bool(forKey: Keys.locationForScans)
        }
        // Custom display name defaults to "" (empty = use the Google
        // session's name). Read as a plain string so SwiftUI bindings
        // bind cleanly to a TextField.
        self.customDisplayName = defaults.string(forKey: Keys.customDisplayName) ?? ""
        self.phoneNumber          = defaults.string(forKey: Keys.phoneNumber) ?? ""
        self.profilePhotoUri      = defaults.string(forKey: Keys.profilePhotoUri) ?? ""
        self.personalityPunchline = defaults.string(forKey: Keys.personalityPunchline) ?? ""
        // Recent searches — `array(forKey:)` returns `[Any]?` so we
        // narrow defensively. A non-string entry (corrupted prefs,
        // older write format) is silently dropped rather than
        // crashing the screen.
        self.recentSearches = (defaults.array(forKey: Keys.recentSearches) as? [String]) ?? []
        // Appearance — primaryColor defaults to coral, themeMode to
        // system. Both round-trip through their enum's `rawValue`
        // so unknown / corrupted strings fall back gracefully.
        self.primaryColor = PrimaryColor(rawValue: defaults.string(forKey: Keys.primaryColor) ?? "")
            ?? .coral
        self.themeMode    = ThemeMode(rawValue: defaults.string(forKey: Keys.themeMode) ?? "")
            ?? .system
        // Last capture surface — falls back to .document on
        // first launch (when the key is absent) and on any
        // corrupted value via `fromAnalyticsKey`'s default.
        self.lastCaptureMode = CaptureMode.fromAnalyticsKey(defaults.string(forKey: Keys.lastCaptureMode))
    }

    /// Called from the onboarding sign-in screen after
    /// `markComplete()` so the user's choice propagates into
    /// Settings. Centralising the write here means the
    /// onboarding state holder doesn't need to know the Settings
    /// keyspace.
    public static func commitOnboardingChoices(driveBackupEnabled: Bool) {
        UserDefaults.standard.set(driveBackupEnabled, forKey: Keys.driveBackup)
    }

    /// Last-known lifetime Tree-points balance. Written by HomeScreen
    /// whenever the SustainabilityHero recomputes (i.e. when the
    /// captured-page count observation pushes a new total), read by
    /// `LaunchAnimationView` at splash time so the cinematic counter
    /// pill ticks up to the user's actual current value rather than
    /// a hardcoded preview number. Defaults to 0 for first-launch /
    /// fresh-install where there's nothing to display yet.
    ///
    /// Lives as a `static` getter/setter (not an @Published instance
    /// var) because the splash runs *before* QuickInkRoot's body
    /// instantiates the SettingsState ObservableObject — the read
    /// path needs to work without a live instance.
    ///
    /// Counterpart: Android `SettingsPreferences.cachedTreePoints`.
    public static var cachedTreePoints: Int {
        get { UserDefaults.standard.integer(forKey: Keys.cachedTreePoints) }
        set { UserDefaults.standard.set(newValue, forKey: Keys.cachedTreePoints) }
    }

    /// Drop every identity-leaking pref on sign-out so the next
    /// account on the same device doesn't inherit the previous
    /// user's custom display name / phone / photo / punchline /
    /// search MRU. Device-level prefs (theme mode, primary color,
    /// drive backup, experimental flags) are intentionally
    /// preserved — those are "the device's preference", not "this
    /// user's preference."
    ///
    /// `static` so QuickInkRoot can call it without holding a live
    /// SettingsState — the in-flight ObservableObject's @Published
    /// vars get refreshed lazily on next read since they're backed
    /// by UserDefaults.
    ///
    /// Mirror of Android `SettingsPreferences.clearAllUserOverrides()`.
    /// Keep the key list in lockstep — adding a new identity-
    /// leaking pref means adding the matching `.removeObject(...)`
    /// here AND the matching Android remove in the same commit.
    public static func clearAllUserOverrides() {
        let defaults = UserDefaults.standard
        defaults.removeObject(forKey: Keys.customDisplayName)
        defaults.removeObject(forKey: Keys.phoneNumber)
        defaults.removeObject(forKey: Keys.profilePhotoUri)
        defaults.removeObject(forKey: Keys.personalityPunchline)
        defaults.removeObject(forKey: Keys.recentSearches)
    }

    private enum Keys {
        static let driveBackup          = "quickink.settings.drive_backup_enabled"
        static let searchablePdfExport  = "quickink.settings.searchable_pdf_export_enabled"
        static let locationForScans     = "quickink.settings.location_for_scans_enabled"
        static let customDisplayName    = "quickink.settings.custom_display_name"
        static let phoneNumber          = "quickink.settings.phone_number"
        static let profilePhotoUri      = "quickink.settings.profile_photo_uri"
        static let personalityPunchline = "quickink.settings.personality_punchline"
        static let recentSearches       = "quickink.settings.recent_searches"
        static let primaryColor         = "quickink.settings.primary_color"
        static let themeMode            = "quickink.settings.theme_mode"
        static let cachedTreePoints     = "quickink.settings.cached_tree_points"
        // Public key per spec (`quickink.capture.last_mode`).
        // Literal here so the on-disk shape stays grep-able and
        // matches the Android SharedPreferences key 1:1.
        static let lastCaptureMode      = "quickink.capture.last_mode"
    }
}
