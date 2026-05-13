/*
 * WorkspaceFeatureFlag.kt
 *
 * SharedPreferences-backed flag that gates the Workspace v1 home
 * screen (Phase B). When OFF, the bottom-nav "Library" tab routes
 * to the existing [NotesListScreen]; when ON, it routes to the new
 * [WorkspaceHomeScreen] and the tab label flips to "Workspace".
 *
 * Default: OFF. Flip via a future dev-menu toggle (or via adb
 * `setprop quickink.workspace.enabled true` once we add that
 * plumbing).
 *
 * Lives outside [SettingsPreferences] because Workspace is staged
 * separately and we want the flag toggle to be easy to find when
 * reviewing the rollout. Mirror of iOS `WorkspaceFeatureFlag.swift`
 * (lands in the iOS Phase B pass).
 */

package app.quickink.mobile.features.workspace

import android.content.Context

object WorkspaceFeatureFlag {

    private const val PREFS = "quickink_workspace"
    private const val KEY_ENABLED = "workspace-v1-enabled"

    /**
     * True when the new Workspace home is active. Safe to call on
     * the UI thread — reads from SharedPreferences are in-process.
     */
    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    /**
     * Default for the flag. Off during Phase A/B build-out so the
     * existing Library UI ships unchanged; flips to true in Phase B
     * when the new home is ready for general rollout.
     */
    private const val DEFAULT_ENABLED = false
}
