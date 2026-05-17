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
import app.quickink.mobile.features.scan.CaptureMode
import app.quickink.mobile.ui.theme.PrimaryColor
import app.quickink.mobile.ui.theme.ThemeMode

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
     * Stories Phase 6 — public-link publishing. When false, the
     * share sheet's Public link tile is grayed out + toasts to flip
     * this on; when true, the tile triggers the confirm dialog and
     * the (stubbed) publisher. Default off per
     * STORIES_HANDOFF.md §6 "default off in TestFlight, on for
     * internal."
     */
    var experimentalPublicLinksEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXPERIMENTAL_PUBLIC_LINKS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_EXPERIMENTAL_PUBLIC_LINKS, value).apply()
        }

    /**
     * When true, the scan + import flows fetch the device's current
     * location (with reverse-geocoded city / area) and attach it to
     * the capture row. When false, the scan flow skips the fetch
     * entirely — captures save with NULL latitude / longitude /
     * locality columns. Default true so users who grant permission
     * during onboarding see the feature working immediately; the
     * Settings → Location row lets them turn it off without
     * revoking system permission.
     */
    var locationForScansEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_FOR_SCANS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LOCATION_FOR_SCANS, value).apply()
        }

    /**
     * User-overridden display name shown on the Home greeting. Empty
     * string means "fall back to the Google account's display name" —
     * resolved at the Home screen call site. Editable from the
     * Settings → Account section so the user can pick what the app
     * calls them without rewriting their Google profile.
     */
    var customDisplayName: String
        get() = prefs.getString(KEY_CUSTOM_DISPLAY_NAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_DISPLAY_NAME, value).apply()
        }

    /**
     * User's phone number, edited from the Profile screen. Free-form
     * string (no E.164 normalization yet); the field is purely
     * cosmetic / for the user's reference.
     */
    var phoneNumber: String
        get() = prefs.getString(KEY_PHONE_NUMBER, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PHONE_NUMBER, value).apply()
        }

    /**
     * `file://` URI of the user's chosen profile photo. Empty when
     * none has been picked — the avatar then falls back to initial /
     * person glyph. The picked image is copied into the app's
     * filesDir so the URI keeps resolving across launches and
     * survives the original gallery URI being revoked.
     */
    var profilePhotoUri: String
        get() = prefs.getString(KEY_PROFILE_PHOTO_URI, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PROFILE_PHOTO_URI, value).apply()
        }

    /**
     * Free-text "personality punchline" — a one-liner the user
     * writes for themselves. Surfaced on the Profile screen only
     * for now.
     */
    var personalityPunchline: String
        get() = prefs.getString(KEY_PERSONALITY_PUNCHLINE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PERSONALITY_PUNCHLINE, value).apply()
        }

    /**
     * MRU list of the user's recent search queries — surfaced as
     * pills under the Search screen's input. Stored as a single
     * Unit-Separator-delimited string (a 0x01 control char that no
     * keyboard can produce) to avoid pulling in a JSON
     * dependency for ten short strings.
     *
     * Reads return newest-first and capped at [RECENT_SEARCHES_MAX].
     * Writes via [pushRecentSearch] dedupe + cap.
     */
    val recentSearches: List<String>
        get() = prefs.getString(KEY_RECENT_SEARCHES, null)
            ?.split(RECENT_SEARCH_DELIMITER)
            ?.filter { it.isNotBlank() }
            ?.take(RECENT_SEARCHES_MAX)
            ?: emptyList()

    /**
     * Push [query] to the front of the recent-searches list, remove
     * any prior occurrence, cap at [RECENT_SEARCHES_MAX]. Trims
     * whitespace and ignores empty queries — typing one character
     * then deleting it shouldn't pollute the pill row.
     */
    fun pushRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val current = recentSearches.filter { !it.equals(trimmed, ignoreCase = true) }
        val next = (listOf(trimmed) + current).take(RECENT_SEARCHES_MAX)
        prefs.edit().putString(KEY_RECENT_SEARCHES, next.joinToString(RECENT_SEARCH_DELIMITER.toString())).apply()
    }

    /** Wipe the recent-searches list. Bound to a "Clear" affordance. */
    fun clearRecentSearches() {
        prefs.edit().remove(KEY_RECENT_SEARCHES).apply()
    }

    /**
     * Drop every identity-leaking override on sign-out so the next
     * account on the same device doesn't inherit the previous user's
     * custom display name / phone / photo / punchline / search MRU.
     * Device-level prefs (theme mode, primary color, drive backup,
     * experimental flags) are intentionally preserved — those are
     * "the device's preference", not "this user's preference."
     *
     * Mirror of iOS `SettingsState.clearAllUserOverrides()`. Keep the
     * key list in lockstep with the Android-only fields above —
     * adding a new identity-leaking pref means adding the matching
     * `.remove(...)` here AND the matching iOS clear in the iOS
     * file in the same commit.
     */
    fun clearAllUserOverrides() {
        prefs.edit()
            .remove(KEY_CUSTOM_DISPLAY_NAME)
            .remove(KEY_PHONE_NUMBER)
            .remove(KEY_PROFILE_PHOTO_URI)
            .remove(KEY_PERSONALITY_PUNCHLINE)
            .remove(KEY_RECENT_SEARCHES)
            .apply()
    }

    /**
     * User's picked primary color (Coral / Leaf Green / Leaf Yellow /
     * Leaf Dry). The theme entry point reads this every composition
     * and resolves the actual `accent` / `accentDeep` from the picked
     * family's (base, deep) pair. Stored as the enum's `name` so the
     * keyspace stays stable across renames at the value site.
     */
    var primaryColor: PrimaryColor
        get() = PrimaryColor.fromKey(prefs.getString(KEY_PRIMARY_COLOR, null))
        set(value) {
            prefs.edit().putString(KEY_PRIMARY_COLOR, value.key).apply()
        }

    /**
     * User's theme override. `System` (default) follows the OS setting;
     * `Light` / `Dark` force the corresponding mode. The theme entry
     * point reads this and feeds it to `isSystemInDarkTheme()`-equivalent
     * resolution.
     */
    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.key).apply()
        }

    /**
     * Last capture surface the user picked on QuickCaptureScreen
     * — Document or BusinessCard. Persisted across sessions so the
     * pill toggle remembers their choice. First-launch fallback is
     * Document; landing card-first would surprise users who came
     * for document scanning. Storage key matches iOS:
     * `quickink.capture.last_mode`.
     */
    var lastCaptureMode: CaptureMode
        get() = CaptureMode.fromAnalyticsKey(prefs.getString(KEY_LAST_CAPTURE_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_LAST_CAPTURE_MODE, value.analyticsKey).apply()
        }

    companion object {
        private const val PREFS_NAME              = "quickink.settings"
        private const val KEY_DRIVE_BACKUP        = "drive_backup_enabled"
        private const val KEY_SEARCHABLE_PDF      = "searchable_pdf_export_enabled"
        private const val KEY_EXPERIMENTAL_PUBLIC_LINKS = "experimental_public_links_enabled"
        private const val KEY_LOCATION_FOR_SCANS  = "location_for_scans_enabled"
        private const val KEY_CUSTOM_DISPLAY_NAME = "custom_display_name"
        private const val KEY_PHONE_NUMBER        = "phone_number"
        private const val KEY_PROFILE_PHOTO_URI   = "profile_photo_uri"
        private const val KEY_PERSONALITY_PUNCHLINE = "personality_punchline"
        private const val KEY_RECENT_SEARCHES     = "recent_searches"
        private const val RECENT_SEARCHES_MAX     = 10
        private const val RECENT_SEARCH_DELIMITER = '\u0001'
        private const val KEY_PRIMARY_COLOR       = "primary_color"
        private const val KEY_THEME_MODE          = "theme_mode"
        private const val KEY_CACHED_TREE_POINTS  = "cached_tree_points"
        private const val KEY_LAST_PAPER_SIZE     = "last_paper_size"
        // Public key per spec (`quickink.capture.last_mode`). Kept
        // literal here so the on-disk shape stays grep-able and
        // matches the iOS UserDefaults key 1:1.
        private const val KEY_LAST_CAPTURE_MODE   = "quickink.capture.last_mode"

        /**
         * Last-known lifetime Tree-points balance. Written by
         * HomeScreen whenever the SustainabilityHero recomputes (i.e.
         * when the `observeTotalPageCount` flow pushes a new total),
         * read by [QuickInkLaunchAnimation] at splash time so the
         * cinematic counter pill ticks up to the user's actual current
         * value rather than a hardcoded preview number. Defaults to 0
         * for first-launch / fresh-install where there's nothing to
         * display yet.
         *
         * Lives as a `static`-style helper on the companion (rather
         * than an instance property) because the splash composable
         * runs at MainActivity onCreate, which is before the
         * `SettingsPreferences(context)` instance the rest of the
         * app uses is constructed in QuickInkRoot — the read path
         * needs to work without a live instance.
         *
         * Counterpart: iOS `SettingsState.cachedTreePoints`.
         */
        fun readCachedTreePoints(context: Context): Int =
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_CACHED_TREE_POINTS, 0)

        fun writeCachedTreePoints(context: Context, points: Int) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CACHED_TREE_POINTS, points)
                .apply()
        }

        /**
         * Last `PaperSize` raw value the user picked on the
         * ScanReviewScreen paper-size chip. Used as the default for
         * the next scan's chip, and consulted by the auto-classifier
         * in `ScanFlowController` to disambiguate A4 vs A5 within
         * the A-series ratio bucket (which aspect ratio alone cannot
         * resolve — they share 1:√2 by ISO design). `null` on
         * fresh-install / pre-feature builds; callers treat that as
         * "no preference, fall back to `a4`".
         *
         * Companion-scoped (rather than an instance property) so
         * `ScanFlowController` can read it without holding a live
         * `SettingsPreferences` — same justification as
         * [readCachedTreePoints].
         *
         * Counterpart: iOS `SettingsState.lastPaperSize`.
         */
        fun readLastPaperSize(context: Context): String? =
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_PAPER_SIZE, null)

        fun writeLastPaperSize(context: Context, raw: String) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_PAPER_SIZE, raw)
                .apply()
        }
    }
}
