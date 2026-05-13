/*
 * WorkspaceFeatureFlag.swift
 *
 * UserDefaults-backed flag that gates the Workspace v1 home screen
 * (Phase B). When OFF, the bottom-nav "Library" tab routes to the
 * existing `NotesListScreen`; when ON, it routes to
 * `WorkspaceHomeScreen` and the tab label flips to "Workspace".
 *
 * Default: OFF. Flip via a future dev-menu toggle (or via the test
 * harness directly).
 *
 * Mirror of `WorkspaceFeatureFlag.kt` in QuickInk's Android target.
 */

import Foundation

public enum WorkspaceFeatureFlag {

    private static let key = "quickink.workspace.v1-enabled"

    /// Default for the flag. Off during Phase A/B build-out so the
    /// existing Library UI ships unchanged; flips to true in Phase
    /// B when the new home is ready for general rollout.
    private static let defaultEnabled = false

    public static func isEnabled() -> Bool {
        UserDefaults.standard.object(forKey: key) as? Bool ?? defaultEnabled
    }

    public static func setEnabled(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: key)
    }
}
