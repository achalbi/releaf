/*
 * SettingsPreferences.kt
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
 *                                `:shared:scan`. v1 default off
 *                                per QUICKINK_PROPOSAL.md §6.3.
 *
 * Storage uses a separate `SharedPreferences` file
 * (`quickink.settings`) from `OnboardingPreferences`'s
 * `quickink.onboarding` so a future onboarding-flow versioning
 * bump doesn't churn user settings.
 *
 * Mirror of iOS `SettingsState.swift`'s persistence shape.
 */

package app.quickink.mobile.features.settings

import android.content.Context
import android.content.SharedPreferences

class SettingsPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var driveBackupEnabled: Boolean
        // Default true — Drive sync is the value prop. Users who
        // explicitly opt out toggle it via Settings.
        get() = prefs.getBoolean(KEY_DRIVE_BACKUP, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DRIVE_BACKUP, value).apply()
        }

    var searchablePdfExportEnabled: Boolean
        // Default false — experimental flag. A future build-time
        // gate hides this even when the runtime flag's on for App
        // Store builds; that gate isn't wired yet (Slice 5 ships
        // it as a pure runtime flag).
        get() = prefs.getBoolean(KEY_SEARCHABLE_PDF, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SEARCHABLE_PDF, value).apply()
        }

    /**
     * User-overridden display name shown on the Home greeting. Empty
     * string means "fall back to the Google account's display name" —
     * resolved at the Home screen call site. Editable from the
     * Settings → Account section so the user can pick what the app
     * calls them without rewriting their Google profile.
     */
    var customDisplayName: String
        get() = prefs.getString(KEY_CUSTOM_DISPLAY_NAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_DISPLAY_NAME, value).apply()
        }

    companion object {
        private const val PREFS_NAME             = "quickink.settings"
        private const val KEY_DRIVE_BACKUP       = "drive_backup_enabled"
        private const val KEY_SEARCHABLE_PDF     = "searchable_pdf_export_enabled"
        private const val KEY_CUSTOM_DISPLAY_NAME = "custom_display_name"
    }
}
